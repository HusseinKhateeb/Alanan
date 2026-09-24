package com.alanan.accounting.users;

import com.alanan.accounting.auth.AuthUser;
import com.alanan.accounting.auth.RefreshSessions;
import com.alanan.accounting.common.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserManagement {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final ObjectMapper json;
    private final RefreshSessions sessions;
    public UserManagement(JdbcTemplate jdbc, PasswordEncoder passwords, ObjectMapper json, RefreshSessions sessions) {
        this.jdbc=jdbc; this.passwords=passwords; this.json=json; this.sessions=sessions;
    }
    public record RoleOption(String name, List<String> permissions) {}
    public record PermissionOption(String code, String description) {}
    public record Options(List<RoleOption> roles, List<PermissionOption> permissions) {}
    public record CreatedUser(UUID id, String username, String displayName, String preferredLanguage,
                              boolean active, List<String> roles, List<String> permissions) {}
    public record UserAccess(UUID id, String username, String displayName, boolean active,
                             List<String> roles, List<String> permissions) {}

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USERS_MANAGE')")
    public UserAccess detail(UUID id) {
        var rows = jdbc.query("SELECT username,display_name,active FROM users WHERE id=? AND deleted_at IS NULL",
            (rs, row) -> new UserAccess(id, rs.getString("username"), rs.getString("display_name"), rs.getBoolean("active"),
                List.of(), List.of()), id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        var account = rows.getFirst();
        var roles = jdbc.queryForList("SELECT r.name FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.name",
            String.class, id);
        var permissions = effectivePermissions(id);
        return new UserAccess(id, account.username(), account.displayName(), account.active(), roles, permissions);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USERS_MANAGE')")
    public UserAccess updateAccess(UUID id, UpdateUserAccessRequest input, AuthUser actor) {
        if (id.equals(actor.id())) throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_EDIT_SELF_ACCESS");
        jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
        var before = detail(id);
        var roles = new TreeSet<>(input.roles());
        var desired = new TreeSet<>(input.permissions());
        var available = options();
        var roleNames = new HashSet<String>();
        available.roles().forEach(role -> roleNames.add(role.name()));
        var permissionCodes = new HashSet<String>();
        available.permissions().forEach(permission -> permissionCodes.add(permission.code()));
        if (roles.isEmpty() || !roleNames.containsAll(roles)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLE");
        if (!permissionCodes.containsAll(desired)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PERMISSION");
        if (before.active() && before.roles().contains("ADMIN") && !roles.contains("ADMIN") && activeAdminCount() <= 1)
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN");
        var base = new HashSet<String>();
        available.roles().stream().filter(role -> roles.contains(role.name())).forEach(role -> base.addAll(role.permissions()));
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", id);
        for (var role : roles) jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?", id, role);
        jdbc.update("DELETE FROM user_permission_overrides WHERE user_id=?", id);
        for (var permission : permissionCodes) {
            boolean granted = desired.contains(permission);
            if (granted != base.contains(permission)) jdbc.update("""
                INSERT INTO user_permission_overrides(user_id,permission_id,granted)
                SELECT ?,id,? FROM permissions WHERE code=?
                """, id, granted, permission);
        }
        jdbc.update("UPDATE users SET token_version=token_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        sessions.revokeAll(id);
        var after = detail(id);
        audit(id, actor.id(), "ACCESS_UPDATED", before, after);
        return after;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USERS_MANAGE')")
    public void remove(UUID id, AuthUser actor) {
        if (id.equals(actor.id())) throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_REMOVE_SELF");
        jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
        var before = detail(id);
        if (before.active() && before.roles().contains("ADMIN") && activeAdminCount() <= 1)
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN");
        jdbc.update("UPDATE users SET active=false, deleted_at=CURRENT_TIMESTAMP, token_version=token_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
        sessions.revokeAll(id);
        audit(id, actor.id(), "REMOVE", before, Map.of("active", false, "removed", true));
    }

    private int activeAdminCount() {
        return jdbc.queryForObject("""
            SELECT count(*) FROM users u JOIN user_roles ur ON ur.user_id=u.id
            JOIN roles r ON r.id=ur.role_id WHERE u.active AND u.deleted_at IS NULL AND r.name='ADMIN'
            """, Integer.class);
    }

    private List<String> effectivePermissions(UUID id) {
        return jdbc.queryForList("""
            SELECT p.code FROM permissions p WHERE COALESCE(
                (SELECT o.granted FROM user_permission_overrides o WHERE o.user_id=? AND o.permission_id=p.id),
                EXISTS(SELECT 1 FROM role_permissions rp JOIN user_roles ur ON ur.role_id=rp.role_id
                       WHERE ur.user_id=? AND rp.permission_id=p.id)) ORDER BY p.code
            """, String.class, id, id);
    }

    private void audit(UUID target, UUID actor, String action, Object before, Object after) {
        jdbc.update("INSERT INTO user_audit(target_user_id,user_id,action,new_values) VALUES (?,?,?,?::jsonb)",
            target, actor, action, json.valueToTree(Map.of("before", before, "after", after)).toString());
    }

    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USERS_MANAGE')")
    public Options options() {
        var roles=jdbc.queryForList("SELECT name FROM roles ORDER BY name", String.class).stream()
            .map(name -> new RoleOption(name, jdbc.queryForList("""
                SELECT p.code FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id
                JOIN roles r ON r.id=rp.role_id WHERE r.name=? ORDER BY p.code
                """, String.class, name))).toList();
        var permissions=jdbc.query("SELECT code,description FROM permissions ORDER BY code",
            (rs,row) -> new PermissionOption(rs.getString("code"), rs.getString("description")));
        return new Options(roles,permissions);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN') and hasAuthority('USERS_MANAGE')")
    public CreatedUser create(CreateUserRequest input, AuthUser actor) {
        if (input.password().length()<6 || input.password().getBytes(StandardCharsets.UTF_8).length>72)
            throw new ApiException(HttpStatus.BAD_REQUEST,"PASSWORD_POLICY");
        var roles=new TreeSet<>(input.roles());
        var desired=new TreeSet<>(input.permissions());
        var available=options(); // Same transaction; role/permission definitions are not user-editable here.
        var roleNames=new HashSet<String>();
        available.roles().forEach(role -> roleNames.add(role.name()));
        var permissionCodes=new HashSet<String>();
        available.permissions().forEach(permission -> permissionCodes.add(permission.code()));
        if (!roleNames.containsAll(roles) || roles.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_ROLE");
        if (!permissionCodes.containsAll(desired)) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PERMISSION");
        if (jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(username)=lower(?)",Long.class,input.username())>0)
            throw new ApiException(HttpStatus.CONFLICT,"USERNAME_EXISTS");
        var base=new HashSet<String>();
        available.roles().stream().filter(role -> roles.contains(role.name())).forEach(role -> base.addAll(role.permissions()));
        var id=UUID.randomUUID();
        var displayName=input.displayName().strip();
        jdbc.update("INSERT INTO users(id,username,display_name,password_hash,preferred_language,active) VALUES (?,?,?,?,?,?)",
            id,input.username(),displayName,passwords.encode(input.password()),input.preferredLanguage(),input.active());
        for (var role : roles) jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE name=?",id,role);
        for (var permission : permissionCodes) {
            boolean granted=desired.contains(permission);
            if (granted!=base.contains(permission)) jdbc.update("""
                INSERT INTO user_permission_overrides(user_id,permission_id,granted)
                SELECT ?,id,? FROM permissions WHERE code=?
                """,id,granted,permission);
        }
        var created=new CreatedUser(id,input.username(),displayName,input.preferredLanguage(),input.active(),List.copyOf(roles),List.copyOf(desired));
        jdbc.update("INSERT INTO user_audit(target_user_id,user_id,action,new_values) VALUES (?,?,?,?::jsonb)",
            id,actor.id(),"CREATE",json.valueToTree(created).toString());
        return created;
    }
}
