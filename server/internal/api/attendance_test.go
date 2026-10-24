package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func vec(seed float32) []float32 {
	v := make([]float32, embeddingDims)
	for i := range v {
		v[i] = seed + float32(i)/1000
	}
	return v
}

func userID(h *harness, code string) string {
	var id string
	h.db.QueryRow(context.Background(), `SELECT id::text FROM users WHERE employee_code = upper($1)`, code).Scan(&id)
	return id
}

func sha(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func TestFaceEnrollmentFlow(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	h.user("insp", "INSPECTOR")
	h.user("other", "INSPECTOR")
	admin, insp, other := h.login("deva"), h.login("insp"), h.login("other")

	var me map[string]any
	h.do("GET", "/v1/face/me", insp, nil, &me)
	if me["approved"] != nil || me["model"] != faceModelName {
		t.Fatalf("fresh user face: %v", me)
	}

	photo := []byte("\xff\xd8 enrolment photo \xff\xd9")
	enrol := func(tok, id string, shots int) int {
		emb := make([][]float32, shots)
		for i := range emb {
			emb[i] = vec(float32(i))
		}
		return h.do("POST", "/v1/face/enrollments", tok, map[string]any{
			"id": id, "model": faceModelName, "embeddings": emb, "photo_sha256": sha(photo), "photo_size": len(photo)}, nil)
	}
	if st := h.do("POST", "/v1/face/enrollments", insp, map[string]any{
		"id": newUUID(), "model": faceModelName, "embeddings": [][]float32{{1, 2}}, "photo_sha256": sha(photo), "photo_size": len(photo)}, nil); st != 400 {
		t.Fatalf("wrong embedding size accepted: %d", st)
	}
	if st := enrol(insp, newUUID(), maxEnrollShots+1); st != 400 {
		t.Fatalf("too many shots accepted: %d", st)
	}
	first := newUUID()
	if st := enrol(insp, first, 5); st != 200 {
		t.Fatalf("enrol: %d", st)
	}
	if st := enrol(insp, first, 5); st != 200 {
		t.Fatalf("enrol retry: %d", st)
	}
	if st := enrol(other, first, 5); st != 403 {
		t.Fatalf("someone else's enrolment id: %d", st)
	}
	if st := h.do("POST", "/v1/admin/face/enrollments/"+first+"/decision", insp, map[string]bool{"approve": true}, nil); st != 403 {
		t.Fatalf("inspector approved: %d", st)
	}
	// The admin can't approve a face they can't see yet.
	if st := h.do("POST", "/v1/admin/face/enrollments/"+first+"/decision", admin, map[string]bool{"approve": true}, nil); st != 409 {
		t.Fatalf("approved before photo: %d", st)
	}
	if st := h.do("PUT", "/v1/face/enrollments/"+first+"/photo", other, photo, nil); st != 403 {
		t.Fatalf("other uploaded photo: %d", st)
	}
	if st := h.do("PUT", "/v1/face/enrollments/"+first+"/photo", insp, []byte("\xff\xd8 tampered photo \xff\xd9"), nil); st != 422 && st != 400 {
		t.Fatalf("bad photo accepted: %d", st)
	}
	if st := h.do("PUT", "/v1/face/enrollments/"+first+"/photo", insp, photo, nil); st != 200 {
		t.Fatalf("photo: %d", st)
	}
	if st := h.do("GET", "/v1/face/enrollments/"+first+"/photo", other, nil, nil); st != 404 {
		t.Fatalf("other read photo: %d", st)
	}
	if st := h.do("GET", "/v1/face/enrollments/"+first+"/photo", admin, nil, nil); st != 200 {
		t.Fatalf("admin read photo: %d", st)
	}
	var list struct {
		Enrollments []struct {
			ID, Status   string
			EmployeeCode string `json:"employee_code"`
			Shots        int
		}
	}
	h.do("GET", "/v1/admin/face/enrollments", admin, nil, &list)
	if len(list.Enrollments) != 1 || list.Enrollments[0].Shots != 5 {
		t.Fatalf("admin list: %+v", list)
	}
	if st := h.do("POST", "/v1/admin/face/enrollments/"+first+"/decision", admin, map[string]bool{"approve": true}, nil); st != 200 {
		t.Fatalf("approve: %d", st)
	}
	if st := h.do("POST", "/v1/admin/face/enrollments/"+first+"/decision", admin, map[string]bool{"approve": true}, nil); st != 409 {
		t.Fatalf("double approve: %d", st)
	}
	var mine struct {
		Approved *enrollmentView
		Pending  *enrollmentView
	}
	h.do("GET", "/v1/face/me", insp, nil, &mine)
	if mine.Approved == nil || mine.Approved.ID != first || len(mine.Approved.Embeddings) != 5 || mine.Pending != nil {
		t.Fatalf("approved face: %+v", mine)
	}

	// Re-enrolment: the old approval keeps working until the new one is approved.
	second := newUUID()
	enrol(insp, second, 3)
	h.do("PUT", "/v1/face/enrollments/"+second+"/photo", insp, photo, nil)
	mine.Approved, mine.Pending = nil, nil
	h.do("GET", "/v1/face/me", insp, nil, &mine)
	if mine.Approved == nil || mine.Approved.ID != first || mine.Pending == nil || mine.Pending.ID != second || mine.Pending.Embeddings != nil {
		t.Fatalf("pending re-enrolment: %+v", mine)
	}
	h.do("POST", "/v1/admin/face/enrollments/"+second+"/decision", admin, map[string]bool{"approve": true}, nil)
	mine.Approved, mine.Pending = nil, nil
	h.do("GET", "/v1/face/me", insp, nil, &mine)
	if mine.Approved == nil || mine.Approved.ID != second || mine.Pending != nil {
		t.Fatalf("after second approval: %+v", mine)
	}
	// An enrolment from an older model is ignored everywhere (embeddings don't compare across models).
	h.db.Exec(context.Background(), `UPDATE face_enrollments SET model = 'old-model' WHERE id = $1`, second)
	mine.Approved = nil
	h.do("GET", "/v1/face/me", insp, nil, &mine)
	if mine.Approved != nil {
		t.Fatalf("old-model face still used: %+v", mine.Approved)
	}
	list.Enrollments = nil
	h.do("GET", "/v1/admin/face/enrollments", admin, nil, &list)
	if len(list.Enrollments) != 0 {
		t.Fatalf("old-model enrolment listed: %+v", list)
	}
	h.db.Exec(context.Background(), `UPDATE face_enrollments SET model = $2 WHERE id = $1`, second, faceModelName)

	// Reset: rejecting the approved face means the engineer must enrol again.
	if st := h.do("POST", "/v1/admin/face/enrollments/"+second+"/decision", admin, map[string]bool{"approve": false}, nil); st != 200 {
		t.Fatalf("reset: %d", st)
	}
	mine.Approved = nil
	h.do("GET", "/v1/face/me", insp, nil, &mine)
	if mine.Approved != nil {
		t.Fatalf("still approved after reset: %+v", mine.Approved)
	}
}

func TestAttendanceRecords(t *testing.T) {
	h := setup(t)
	h.user("deva", "ADMIN")
	h.user("insp", "INSPECTOR")
	h.user("other", "INSPECTOR")
	admin, insp, other := h.login("deva"), h.login("insp"), h.login("other")

	selfie := []byte("\xff\xd8 selfie bytes \xff\xd9")
	base := time.Date(2026, 10, 21, 4, 30, 0, 0, time.UTC).UnixMilli() // 10:00 IST
	record := func(tok, id, kind string, at int64, extra map[string]any) int {
		body := map[string]any{
			"id": id, "kind": kind, "client_at": at, "time_zone": "Asia/Kolkata",
			"lat": 16.3067, "lng": 80.4365, "accuracy_m": 12.5, "location_at": at,
			"face_result": "MATCH", "face_score": 0.71, "liveness_passed": true,
			"selfie_sha256": sha(selfie), "selfie_size": len(selfie),
		}
		for k, v := range extra {
			body[k] = v
		}
		return h.do("POST", "/v1/attendance", tok, body, nil)
	}
	in := newUUID()
	if st := record(insp, in, "IN", base, map[string]any{
		"elapsed_ms": 1_000_000, "boot_count": 7, "now_elapsed_ms": 1_060_000, "now_boot_count": 7}); st != 200 {
		t.Fatalf("check-in: %d", st)
	}
	if st := record(insp, in, "IN", base, nil); st != 200 {
		t.Fatalf("retry: %d", st)
	}
	if st := record(other, newUUID(), "IN", base, map[string]any{"user_id": userID(h, "insp")}); st != 403 {
		t.Fatalf("someone else's record accepted: %d", st)
	}
	if st := record(insp, newUUID(), "IN", base, map[string]any{"user_id": userID(h, "insp"), "selfie_size": 1}); st != 200 {
		t.Fatalf("own record with user_id: %d", st)
	}
	if st := record(other, in, "IN", base, nil); st != 409 {
		t.Fatalf("id reuse by another user: %d", st)
	}
	for name, extra := range map[string]map[string]any{
		"bad kind":     {"kind": "LUNCH"},
		"half a point": {"lng": nil},
		"bad lat":      {"lat": 123.0},
		"bad face":     {"face_result": "MAYBE"},
		"no hash":      {"selfie_sha256": "abc"},
	} {
		if st := record(insp, newUUID(), "IN", base, extra); st != 400 {
			t.Fatalf("%s accepted: %d", name, st)
		}
	}
	// No location fix, rebooted since the event: stored, without a server time estimate.
	out := newUUID()
	if st := record(insp, out, "OUT", base+8*3600_000, map[string]any{
		"lat": nil, "lng": nil, "accuracy_m": nil, "face_result": "NO_MATCH", "is_mock": true,
		"elapsed_ms": 5_000, "boot_count": 7, "now_elapsed_ms": 9_000, "now_boot_count": 8}); st != 200 {
		t.Fatalf("check-out: %d", st)
	}
	record(other, newUUID(), "IN", base+60_000, nil)

	if st := h.do("PUT", "/v1/attendance/"+in+"/selfie", other, selfie, nil); st != 403 {
		t.Fatalf("other uploaded selfie: %d", st)
	}
	if st := h.do("PUT", "/v1/attendance/"+newUUID()+"/selfie", insp, selfie, nil); st != 409 {
		t.Fatalf("selfie before record: %d", st)
	}
	var ok struct{ SHA256 string }
	if st := h.do("PUT", "/v1/attendance/"+in+"/selfie", insp, selfie, &ok); st != 200 || ok.SHA256 != sha(selfie) {
		t.Fatalf("selfie: %d %+v", st, ok)
	}
	if st := h.do("PUT", "/v1/attendance/"+in+"/selfie", insp, selfie, nil); st != 200 {
		t.Fatalf("selfie retry: %d", st)
	}
	if st := h.do("GET", "/v1/attendance/"+in+"/selfie", other, nil, nil); st != 404 {
		t.Fatalf("other read selfie: %d", st)
	}
	if st := h.do("GET", "/v1/attendance/"+in+"/selfie", admin, nil, nil); st != 200 {
		t.Fatalf("admin read selfie: %d", st)
	}

	type rec struct {
		ID, Kind, Selfie string
		EmployeeCode     string `json:"employee_code"`
		FaceResult       string `json:"face_result"`
		EstimatedAt      *int64 `json:"estimated_at"`
		IsMock           bool   `json:"is_mock"`
		Lat              *float64
	}
	var mineList struct{ Records []rec }
	day := fmt.Sprintf("?from=%d&to=%d", base-3600_000, base+20*3600_000)
	if st := h.do("GET", "/v1/attendance"+day, insp, nil, &mineList); st != 200 || len(mineList.Records) != 3 {
		t.Fatalf("own list: %d %+v", st, mineList)
	}
	if st := h.do("GET", "/v1/admin/attendance"+day, insp, nil, nil); st != 403 {
		t.Fatalf("inspector read everyone: %d", st)
	}
	var all struct{ Records []rec }
	h.do("GET", "/v1/admin/attendance"+day, admin, nil, &all)
	if len(all.Records) != 4 {
		t.Fatalf("admin list: %+v", all)
	}
	got := map[string]rec{}
	for _, r := range mineList.Records {
		got[r.ID] = r
	}
	if r := got[in]; r.Selfie != "AVAILABLE" || r.EstimatedAt == nil {
		t.Fatalf("check-in row: %+v", r)
	}
	if r := got[out]; r.Selfie != "UPLOADING" || r.EstimatedAt != nil || !r.IsMock || r.Lat != nil || r.FaceResult != "NO_MATCH" {
		t.Fatalf("check-out row: %+v", r)
	}
	if st := h.do("GET", "/v1/attendance?from=10&to=5", insp, nil, nil); st != 400 {
		t.Fatalf("bad range: %d", st)
	}

	// 90-day selfie retention: the file goes, the record stays.
	var key string
	h.db.QueryRow(context.Background(), `SELECT selfie_key FROM attendance WHERE id = $1`, in).Scan(&key)
	if n, err := h.server.PurgeOldSelfies(context.Background()); err != nil || n != 0 {
		t.Fatalf("fresh selfie purged: %d %v", n, err)
	}
	h.db.Exec(context.Background(), `UPDATE attendance SET received_at = now() - interval '91 days' WHERE id = $1`, in)
	if n, err := h.server.PurgeOldSelfies(context.Background()); err != nil || n != 1 {
		t.Fatalf("purge: %d %v", n, err)
	}
	if _, err := os.Stat(filepath.Join(h.dataDir, key)); !os.IsNotExist(err) {
		t.Fatalf("selfie file still there: %v", err)
	}
	if st := h.do("GET", "/v1/attendance/"+in+"/selfie", admin, nil, nil); st != 410 {
		t.Fatalf("purged selfie: %d", st)
	}
	mineList.Records = nil
	h.do("GET", "/v1/attendance"+day, insp, nil, &mineList)
	if len(mineList.Records) != 3 {
		t.Fatalf("records lost with selfie: %+v", mineList)
	}
}

func TestFaceModelDownload(t *testing.T) {
	h := setup(t)
	h.user("insp", "INSPECTOR")
	tok := h.login("insp")
	if st := h.do("GET", "/v1/face/model", tok, nil, nil); st != 404 {
		t.Fatalf("missing model: %d", st)
	}
	os.MkdirAll(filepath.Join(h.dataDir, "models"), 0o750)
	os.WriteFile(filepath.Join(h.dataDir, "models", faceModelFile), []byte("onnx"), 0o640)
	if st := h.do("GET", "/v1/face/model", tok, nil, nil); st != 200 {
		t.Fatalf("model: %d", st)
	}
	if st := h.do("GET", "/v1/face/model", "", nil, nil); st != 401 {
		t.Fatalf("anonymous model download: %d", st)
	}
}
