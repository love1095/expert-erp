-- Manage dashboard (like Cin7): which parts the dashboard shows, in which order, folded or full width.
-- One row per user (their own choice) and one per organisation with user_id NULL (the default an admin sets).
CREATE TABLE dashboard_layouts (
    id         bigserial PRIMARY KEY,
    tenant_id  bigint NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id    bigint REFERENCES users (id) ON DELETE CASCADE,
    layout     text NOT NULL,
    updated_at timestamp NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_dashboard_layouts_user ON dashboard_layouts (tenant_id, user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX ux_dashboard_layouts_default ON dashboard_layouts (tenant_id) WHERE user_id IS NULL;
