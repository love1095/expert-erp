-- Phase Q5: a user may have several roles; their permissions add up. users.access_role_id stays as the first one.
CREATE TABLE user_access_roles (
    user_id         bigint NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    access_role_id  bigint NOT NULL REFERENCES access_roles (id) ON DELETE CASCADE,
    tenant_id       bigint NOT NULL,
    PRIMARY KEY (user_id, access_role_id)
);
CREATE INDEX idx_user_access_roles_role ON user_access_roles (access_role_id);

INSERT INTO user_access_roles (user_id, access_role_id, tenant_id)
SELECT u.id, u.access_role_id, r.tenant_id FROM users u JOIN access_roles r ON r.id = u.access_role_id;
