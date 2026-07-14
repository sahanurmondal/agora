package api

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"net/url"
	"strings"

	"github.com/sahanurmondal/agora/services/link-service/internal/ids"
	"github.com/sahanurmondal/agora/services/link-service/internal/store"
)

type Handler struct {
	store   *store.Store
	snow    *ids.Snowflake
	baseURL string
}

func New(s *store.Store, snow *ids.Snowflake, baseURL string) *Handler {
	return &Handler{store: s, snow: snow, baseURL: strings.TrimRight(baseURL, "/")}
}

func (h *Handler) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /healthz", h.health)
	mux.HandleFunc("POST /api/v1/links", h.create)
	mux.HandleFunc("GET /api/v1/links/{code}", h.get)
	mux.HandleFunc("GET /{code}", h.redirect)
}

type createReq struct {
	URL string `json:"url"`
}

type createResp struct {
	Code     string `json:"code"`
	ShortURL string `json:"short_url"`
}

func (h *Handler) create(w http.ResponseWriter, r *http.Request) {
	var req createReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		httpError(w, http.StatusBadRequest, "invalid json")
		return
	}
	u, err := url.ParseRequestURI(req.URL)
	if err != nil || (u.Scheme != "http" && u.Scheme != "https") {
		httpError(w, http.StatusBadRequest, "url must be absolute http(s)")
		return
	}

	id := h.snow.Next()
	code := ids.Base62(id)
	if err := h.store.Create(r.Context(), id, code, req.URL); err != nil {
		slog.Error("create link", "err", err)
		httpError(w, http.StatusInternalServerError, "storage failure")
		return
	}
	writeJSON(w, http.StatusCreated, createResp{Code: code, ShortURL: h.baseURL + "/" + code})
}

// redirect answers 302 (not 301): permanent redirects get cached by browsers
// and intermediaries, which would bypass us and kill click analytics — see
// docs/link-service/adr/001-302-over-301.md.
func (h *Handler) redirect(w http.ResponseWriter, r *http.Request) {
	target, _, err := h.store.GetURL(r.Context(), r.PathValue("code"))
	if errors.Is(err, store.ErrNotFound) {
		httpError(w, http.StatusNotFound, "unknown code")
		return
	}
	if err != nil {
		slog.Error("resolve link", "err", err)
		httpError(w, http.StatusInternalServerError, "storage failure")
		return
	}
	http.Redirect(w, r, target, http.StatusFound)
}

func (h *Handler) get(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	target, cacheHit, err := h.store.GetURL(r.Context(), code)
	if errors.Is(err, store.ErrNotFound) {
		httpError(w, http.StatusNotFound, "unknown code")
		return
	}
	if err != nil {
		httpError(w, http.StatusInternalServerError, "storage failure")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"code": code, "url": target, "cache_hit": cacheHit})
}

func (h *Handler) health(w http.ResponseWriter, r *http.Request) {
	if err := h.store.Healthy(r.Context()); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "down", "error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "up"})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func httpError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}
