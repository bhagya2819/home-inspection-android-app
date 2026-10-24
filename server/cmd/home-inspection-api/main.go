// Command home-inspection-api runs the Home Inspection sync server.
//
//	home-inspection-api serve                       migrate the database, then serve HTTP on LISTEN_ADDR
//	home-inspection-api create-user CODE NAME ROLE  create an account; the password is read from stdin
//	home-inspection-api healthcheck                 exit 0 if the local server answers /healthz (Docker)
//	home-inspection-api lock [MESSAGE]              developer lock: every phone shows a "contact your developer" screen
//	home-inspection-api unlock                      lift the developer lock; phones resume at their next server contact
//	home-inspection-api lock-status                 print whether the developer lock is on
package main

import (
	"bufio"
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"home-inspection.in/server/internal/api"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "usage: home-inspection-api serve | create-user CODE NAME ROLE | healthcheck | lock [MESSAGE] | unlock | lock-status")
		os.Exit(2)
	}
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	var err error
	switch os.Args[1] {
	case "serve":
		err = serve(log)
	case "create-user":
		err = createUser()
	case "healthcheck":
		err = healthcheck()
	case "lock":
		err = setLock(true, strings.Join(os.Args[2:], " "))
	case "unlock":
		if len(os.Args) != 2 {
			err = errors.New("usage: home-inspection-api unlock")
		} else {
			err = setLock(false, "")
		}
	case "lock-status":
		err = lockStatus()
	default:
		err = fmt.Errorf("unknown command %q", os.Args[1])
	}
	if err != nil {
		log.Error("fatal", "err", err)
		os.Exit(1)
	}
}

func env(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func connect(ctx context.Context) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(os.Getenv("DATABASE_URL"))
	if err != nil {
		return nil, err
	}
	cfg.MaxConns = 8 // small box, shared with other apps
	var db *pgxpool.Pool
	for attempt := 0; attempt < 30; attempt++ { // Postgres may still be starting
		if db, err = pgxpool.NewWithConfig(ctx, cfg); err == nil {
			if err = db.Ping(ctx); err == nil {
				return db, nil
			}
			db.Close()
		}
		time.Sleep(time.Second)
	}
	return nil, err
}

func serve(log *slog.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()
	db, err := connect(ctx)
	if err != nil {
		return fmt.Errorf("database: %w", err)
	}
	defer db.Close()
	if err := api.Migrate(ctx, db); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	// 32 random bytes as hex (openssl rand -hex 32), kept in the server's .env, never in the database.
	var aiSecret []byte
	if v := os.Getenv("HOME_INSPECTION_SECRET_KEY"); v != "" {
		if aiSecret, err = hex.DecodeString(v); err != nil || len(aiSecret) != 32 {
			return errors.New("HOME_INSPECTION_SECRET_KEY must be 64 hex characters (openssl rand -hex 32)")
		}
	} else {
		log.Warn("HOME_INSPECTION_SECRET_KEY not set: AI suggestions are off")
	}
	srv := api.New(db, api.Config{
		DataDir:        env("DATA_DIR", "/data"),
		MaxUploadBytes: 60 << 20,
		SessionTTL:     30 * 24 * time.Hour,
		AISecret:       aiSecret,
		DeepSeekURL:    os.Getenv("DEEPSEEK_URL"),
		AIModel:        os.Getenv("AI_MODEL"),
	}, log)
	httpServer := &http.Server{
		Addr:              env("LISTEN_ADDR", ":8080"),
		Handler:           srv.Handler(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
		// No overall read/write timeout: photo uploads on slow mobile data can take minutes.
	}
	go srv.RunSelfieRetention(ctx)
	go srv.RunTrackingRetention(ctx)
	go srv.RunDocxCachePurge(ctx)
	errCh := make(chan error, 1)
	go func() { errCh <- httpServer.ListenAndServe() }()
	log.Info("listening", "addr", httpServer.Addr)
	select {
	case err := <-errCh:
		return err
	case <-ctx.Done():
	}
	shutdownCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	return httpServer.Shutdown(shutdownCtx)
}

func createUser() error {
	if len(os.Args) != 5 {
		return errors.New("usage: home-inspection-api create-user CODE \"DISPLAY NAME\" ADMIN|INSPECTOR  (password on stdin)")
	}
	password, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && password == "" {
		return errors.New("password must be given on stdin")
	}
	ctx := context.Background()
	db, err := connect(ctx)
	if err != nil {
		return err
	}
	defer db.Close()
	if err := api.Migrate(ctx, db); err != nil {
		return err
	}
	id, err := api.CreateUser(ctx, db, os.Args[2], os.Args[3], os.Args[4], strings.TrimRight(password, "\r\n"), true)
	if err != nil {
		return err
	}
	fmt.Println(id)
	return nil
}

// setLock is run by the developer over SSH: docker exec home-inspection-api /home-inspection-api lock "message".
// Nothing in the app or the HTTP API can change the lock.
func setLock(locked bool, message string) error {
	ctx := context.Background()
	db, err := connect(ctx)
	if err != nil {
		return err
	}
	defer db.Close()
	if err := api.Migrate(ctx, db); err != nil {
		return err
	}
	if err := api.SetAppLock(ctx, db, locked, message); err != nil {
		return err
	}
	return printLock(ctx, db)
}

func lockStatus() error {
	ctx := context.Background()
	db, err := connect(ctx)
	if err != nil {
		return err
	}
	defer db.Close()
	return printLock(ctx, db)
}

func printLock(ctx context.Context, db *pgxpool.Pool) error {
	locked, message, at, err := api.AppLockStatus(ctx, db)
	if err != nil {
		return err
	}
	if locked {
		fmt.Printf("LOCKED since %s\nmessage: %s\n", at.Local().Format(time.RFC1123), message)
	} else {
		fmt.Printf("unlocked (since %s)\n", at.Local().Format(time.RFC1123))
	}
	return nil
}

func healthcheck() error {
	client := http.Client{Timeout: 3 * time.Second}
	resp, err := client.Get("http://127.0.0.1" + env("LISTEN_ADDR", ":8080") + "/healthz")
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("status %d", resp.StatusCode)
	}
	return nil
}
