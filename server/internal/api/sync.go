package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// ---- Entity definitions --------------------------------------------------------------------
//
// Field names are the JSON keys clients send and receive, and equal the column names. They match
// the Android Room schema 1:1, which keeps the wire format obvious for any future client (iOS).

type colType int

const (
	tText colType = iota
	tTextNull
	tInt
	tIntNull
	tBool
	tUUID // entity id reference
)

type column struct {
	name string
	typ  colType
}

type entity struct {
	typ       string
	table     string
	cols      []column
	immutable map[string]bool // cannot change after insert (photo bytes are evidence)
	// Added after phones were in use: a push that leaves it out (an older app) keeps the stored value.
	keepIfAbsent map[string]bool
	server       []string // extra read-only fields: "<sql expr> AS <json key>"
}

var entities = []*entity{
	{
		typ: "INSPECTION", table: "inspections",
		cols: []column{
			{"client_name", tText}, {"client_phone", tText}, {"property_address", tText}, {"property_type", tTextNull},
			{"unit_number", tTextNull}, {"access_notes", tTextNull}, {"inspector_user_id", tText}, {"inspection_date", tText},
			{"time_zone", tText}, {"status", tText}, {"report_version", tInt}, {"created_at", tInt}, {"updated_at", tInt},
			{"finalized_at", tIntNull}, {"deleted_at", tIntNull},
			{"summary", tTextNull},
		},
		keepIfAbsent: map[string]bool{"summary": true},
	},
	{
		typ: "ROOM", table: "rooms",
		cols: []column{
			{"inspection_id", tUUID}, {"name", tText}, {"sort_order", tInt}, {"is_custom", tBool},
			{"created_at", tInt}, {"updated_at", tInt}, {"deleted_at", tIntNull},
		},
	},
	{
		typ: "FINDING", table: "findings",
		cols: []column{
			{"inspection_id", tUUID}, {"room_id", tUUID}, {"title", tTextNull}, {"category", tTextNull}, {"severity", tTextNull},
			{"rough_note", tText}, {"detailed_observation", tTextNull}, {"implication", tTextNull}, {"recommendation", tTextNull},
			{"status", tText}, {"sort_order", tInt}, {"created_by", tText}, {"created_at", tInt}, {"updated_at", tInt},
			{"deleted_at", tIntNull},
			{"kind", tTextNull},
		},
		keepIfAbsent: map[string]bool{"kind": true},
	},
	{
		typ: "PHOTO", table: "photos",
		cols: []column{
			{"inspection_id", tUUID}, {"room_id", tUUID}, {"finding_id", tUUID}, {"mime_type", tText}, {"width", tInt},
			{"height", tInt}, {"file_size_bytes", tInt}, {"sha256", tText}, {"captured_at", tInt}, {"caption", tTextNull},
			{"created_at", tInt}, {"updated_at", tInt}, {"deleted_at", tIntNull},
		},
		immutable: map[string]bool{"sha256": true, "file_size_bytes": true, "mime_type": true, "width": true, "height": true, "inspection_id": true},
		server: []string{
			"object_key AS server_object_key",
			"(extract(epoch FROM verified_at) * 1000)::bigint AS backup_verified_at",
			"CASE WHEN verified_at IS NULL THEN NULL ELSE sha256 END AS backup_checksum",
			"(extract(epoch FROM export_verified_at) * 1000)::bigint AS export_verified_at",
			"(extract(epoch FROM archived_at) * 1000)::bigint AS archived_at",
		},
	},
	{
		typ: "REPORT", table: "reports",
		cols: []column{
			{"inspection_id", tUUID}, {"version", tInt}, {"content_hash", tText}, {"file_size_bytes", tInt},
			{"generated_at", tInt}, {"finalized_at", tIntNull}, {"shared_at", tIntNull},
		},
		immutable: map[string]bool{"inspection_id": true, "version": true, "content_hash": true, "file_size_bytes": true},
		server: []string{
			"object_key AS server_object_key",
			"(extract(epoch FROM verified_at) * 1000)::bigint AS backup_verified_at",
			"(extract(epoch FROM export_verified_at) * 1000)::bigint AS export_verified_at",
			"(extract(epoch FROM archived_at) * 1000)::bigint AS archived_at",
		},
	},
}

var entityByType = func() map[string]*entity {
	m := map[string]*entity{}
	for _, e := range entities {
		m[e.typ] = e
	}
	return m
}()

const maxTextLen = 20_000

// convert validates one JSON value against its column type.
func convert(c column, v any, present bool) (any, error) {
	if !present || v == nil {
		if c.typ == tTextNull || c.typ == tIntNull {
			return nil, nil
		}
		return nil, fmt.Errorf("%s is required", c.name)
	}
	switch c.typ {
	case tText, tTextNull:
		s, ok := v.(string)
		if !ok || len(s) > maxTextLen {
			return nil, fmt.Errorf("%s must be text up to %d bytes", c.name, maxTextLen)
		}
		return s, nil
	case tUUID:
		s, ok := v.(string)
		if !ok || !isUUID(s) {
			return nil, fmt.Errorf("%s must be a UUID", c.name)
		}
		return s, nil
	case tInt, tIntNull:
		n, ok := v.(json.Number)
		if !ok {
			return nil, fmt.Errorf("%s must be an integer", c.name)
		}
		i, err := n.Int64()
		if err != nil {
			return nil, fmt.Errorf("%s must be an integer", c.name)
		}
		return i, nil
	case tBool:
		b, ok := v.(bool)
		if !ok {
			return nil, fmt.Errorf("%s must be true or false", c.name)
		}
		return b, nil
	}
	return nil, fmt.Errorf("unknown column type")
}

// ---- Push ----------------------------------------------------------------------------------

type pushItem struct {
	Type         string         `json:"type"`
	ID           string         `json:"id"`
	BaseRevision int64          `json:"base_revision"`
	MutationID   string         `json:"mutation_id"` // "<idempotency_key>:<payload_version>"
	Force        bool           `json:"force"`       // conflict resolved by the user as "keep mine"
	Data         map[string]any `json:"data"`
}

type pushResult struct {
	Type     string         `json:"type"`
	ID       string         `json:"id"`
	Status   string         `json:"status"` // OK | CONFLICT | REJECTED | RETRY
	Revision int64          `json:"revision,omitempty"`
	Code     string         `json:"code,omitempty"`
	Message  string         `json:"message,omitempty"`
	Server   map[string]any `json:"server,omitempty"` // current server copy, on CONFLICT
}

// push applies a batch of client writes. Each item commits on its own, so one bad record never
// blocks the rest; results come back in request order (PRD §8.2 steps 1–2).
func (s *Server) push(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Items []pushItem `json:"items"`
	}
	if !readJSON(w, r, 8<<20, &req) {
		return
	}
	if len(req.Items) > 500 {
		writeError(w, http.StatusRequestEntityTooLarge, "TOO_MANY_ITEMS", "send at most 500 items per push")
		return
	}
	u := userFrom(r)
	results := make([]pushResult, len(req.Items))
	for i, item := range req.Items {
		results[i] = s.apply(r.Context(), u, item)
	}
	writeJSON(w, http.StatusOK, map[string]any{"results": results})
}

func (s *Server) apply(ctx context.Context, u *authUser, item pushItem) (res pushResult) {
	res = pushResult{Type: item.Type, ID: item.ID}
	reject := func(code, msg string) pushResult {
		res.Status, res.Code, res.Message = "REJECTED", code, msg
		return res
	}
	e := entityByType[item.Type]
	if e == nil {
		return reject("UNKNOWN_TYPE", "unknown entity type")
	}
	if !isUUID(item.ID) {
		return reject("INVALID", "id must be a UUID")
	}
	if item.MutationID == "" || len(item.MutationID) > 100 {
		return reject("INVALID", "mutation_id is required")
	}
	values := make([]any, len(e.cols))
	absent := make([]bool, len(e.cols))
	for i, c := range e.cols {
		v, present := item.Data[c.name]
		cv, err := convert(c, v, present)
		if err != nil {
			return reject("INVALID", err.Error())
		}
		values[i] = cv
		absent[i] = !present
	}

	err := pgx.BeginFunc(ctx, s.db, func(tx pgx.Tx) error {
		if err := writeLock(ctx, tx); err != nil {
			return err
		}
		var revision int64
		var lastMutation string
		err := tx.QueryRow(ctx, fmt.Sprintf(`SELECT revision, last_mutation_id FROM %s WHERE id = $1 FOR UPDATE`, e.table), item.ID).
			Scan(&revision, &lastMutation)
		switch {
		case errors.Is(err, pgx.ErrNoRows):
			cols := []string{"id"}
			ph := []string{"$1"}
			args := []any{item.ID}
			for i, c := range e.cols {
				cols = append(cols, c.name)
				args = append(args, values[i])
				ph = append(ph, "$"+strconv.Itoa(len(args)))
			}
			args = append(args, item.MutationID, u.ID)
			sql := fmt.Sprintf(`INSERT INTO %s (%s, revision, last_mutation_id, updated_by, change_seq) VALUES (%s, 1, $%d, $%d, nextval('change_seq'))`,
				e.table, strings.Join(cols, ", "), strings.Join(ph, ", "), len(args)-1, len(args))
			if _, err := tx.Exec(ctx, sql, args...); err != nil {
				return err
			}
			res.Status, res.Revision = "OK", 1
			return nil
		case err != nil:
			return err
		}

		if lastMutation == item.MutationID { // a retry of a write we already applied
			res.Status, res.Revision = "OK", revision
			return nil
		}
		if revision != item.BaseRevision && !item.Force {
			// PRD §8.4: never silently overwrite another device's newer edit.
			server, err := s.loadOne(ctx, tx, e, item.ID)
			if err != nil {
				return err
			}
			res.Status, res.Revision, res.Code, res.Server = "CONFLICT", revision, "STALE_REVISION", server
			res.Message = "this record was changed on another device"
			return nil
		}
		if len(e.immutable) > 0 {
			current, err := s.loadOne(ctx, tx, e, item.ID)
			if err != nil {
				return err
			}
			for i, c := range e.cols {
				if e.immutable[c.name] && fmt.Sprint(current[c.name]) != fmt.Sprint(values[i]) {
					res.Status, res.Code, res.Message = "REJECTED", "IMMUTABLE_FIELD", c.name+" cannot change"
					return nil
				}
			}
		}
		sets := make([]string, 0, len(e.cols))
		args := []any{item.ID}
		for i, c := range e.cols {
			if e.immutable[c.name] || (e.keepIfAbsent[c.name] && absent[i]) {
				continue
			}
			args = append(args, values[i])
			sets = append(sets, fmt.Sprintf("%s = $%d", c.name, len(args)))
		}
		args = append(args, item.MutationID, u.ID)
		sql := fmt.Sprintf(`UPDATE %s SET %s, revision = revision + 1, last_mutation_id = $%d, updated_by = $%d,
			server_updated_at = now(), change_seq = nextval('change_seq') WHERE id = $1 RETURNING revision`,
			e.table, strings.Join(sets, ", "), len(args)-1, len(args))
		if err := tx.QueryRow(ctx, sql, args...).Scan(&res.Revision); err != nil {
			return err
		}
		res.Status = "OK"
		return nil
	})
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23503" {
			// Parent not synced yet (e.g. its push failed earlier): the client simply retries later.
			res.Status, res.Code, res.Message, res.Revision, res.Server = "RETRY", "PARENT_MISSING", "parent record not on the server yet", 0, nil
			return res
		}
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			return reject("DUPLICATE", "a record with the same unique fields exists")
		}
		s.log.Error("push", "type", item.Type, "err", err)
		res.Status, res.Code, res.Message, res.Revision, res.Server = "RETRY", "INTERNAL", "server error; will retry", 0, nil
	}
	return res
}

func (e *entity) selectList() string {
	parts := []string{"id::text AS id"}
	for _, c := range e.cols {
		if c.typ == tUUID {
			parts = append(parts, c.name+"::text AS "+c.name)
		} else {
			parts = append(parts, c.name)
		}
	}
	parts = append(parts, "revision")
	parts = append(parts, e.server...)
	parts = append(parts, "change_seq")
	return strings.Join(parts, ", ")
}

func (s *Server) loadOne(ctx context.Context, q pgx.Tx, e *entity, id string) (map[string]any, error) {
	rows, err := q.Query(ctx, fmt.Sprintf(`SELECT %s FROM %s WHERE id = $1`, e.selectList(), e.table), id)
	if err != nil {
		return nil, err
	}
	maps, err := rowsToMaps(rows)
	if err != nil || len(maps) == 0 {
		return nil, err
	}
	delete(maps[0], "change_seq")
	return maps[0], nil
}

func rowsToMaps(rows pgx.Rows) ([]map[string]any, error) {
	defer rows.Close()
	var out []map[string]any
	fields := rows.FieldDescriptions()
	for rows.Next() {
		vals, err := rows.Values()
		if err != nil {
			return nil, err
		}
		m := make(map[string]any, len(vals))
		for i, f := range fields {
			m[f.Name] = vals[i]
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// ---- Changes (pull) ------------------------------------------------------------------------

type change struct {
	Type string         `json:"type"`
	Seq  int64          `json:"-"`
	Data map[string]any `json:"data"`
}

// changes returns every record written after cursor, oldest first, across all types including
// users (display names, roles). Clients store the returned cursor only after applying the page.
func (s *Server) changes(w http.ResponseWriter, r *http.Request) {
	cursor, err := strconv.ParseInt(r.URL.Query().Get("cursor"), 10, 64)
	if err != nil || cursor < 0 {
		cursor = 0
	}
	limit, err := strconv.Atoi(r.URL.Query().Get("limit"))
	if err != nil || limit <= 0 || limit > 1000 {
		limit = 500
	}
	var all []change
	collect := func(typ, sql string) error {
		rows, err := s.db.Query(r.Context(), sql, cursor, limit)
		if err != nil {
			return err
		}
		maps, err := rowsToMaps(rows)
		if err != nil {
			return err
		}
		for _, m := range maps {
			seq := m["change_seq"].(int64)
			delete(m, "change_seq")
			all = append(all, change{Type: typ, Seq: seq, Data: m})
		}
		return nil
	}
	err = collect("USER", `SELECT id::text AS id, employee_code, display_name, role, status, change_seq FROM users
		WHERE change_seq > $1 ORDER BY change_seq LIMIT $2`)
	for _, e := range entities {
		if err != nil {
			break
		}
		err = collect(e.typ, fmt.Sprintf(`SELECT %s FROM %s WHERE change_seq > $1 ORDER BY change_seq LIMIT $2`, e.selectList(), e.table))
	}
	if err != nil {
		s.internal(w, "changes", err)
		return
	}
	// Each table returned its own first `limit`; the global first `limit` is a correct page.
	sort.Slice(all, func(i, j int) bool { return all[i].Seq < all[j].Seq })
	// ">=": a table may have been cut at exactly `limit`; at worst the client asks once more.
	hasMore := len(all) >= limit
	if len(all) > limit {
		all = all[:limit]
	}
	next := cursor
	if len(all) > 0 {
		next = all[len(all)-1].Seq
	}
	if all == nil {
		all = []change{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"changes": all, "cursor": next, "has_more": hasMore})
}
