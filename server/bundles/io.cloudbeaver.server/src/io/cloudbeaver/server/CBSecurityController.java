/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cloudbeaver.server;

import io.cloudbeaver.DBWConnectionGrant;
import io.cloudbeaver.DBWConstants;
import io.cloudbeaver.DBWSecurityController;
import io.cloudbeaver.DBWSecuritySubjectType;
import io.cloudbeaver.model.session.WebSession;
import io.cloudbeaver.model.user.WebRole;
import io.cloudbeaver.model.user.WebUser;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.auth.AuthPropertyDescriptor;
import org.jkiss.dbeaver.model.auth.AuthPropertyEncryption;
import org.jkiss.dbeaver.model.auth.DBAAuthCredentialsProfile;
import org.jkiss.dbeaver.model.auth.DBAAuthProviderDescriptor;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.impl.jdbc.JDBCUtils;
import org.jkiss.dbeaver.model.impl.jdbc.exec.JDBCTransaction;
import org.jkiss.dbeaver.registry.auth.AuthProviderDescriptor;
import org.jkiss.dbeaver.registry.auth.AuthProviderRegistry;
import org.jkiss.utils.ArrayUtils;
import org.jkiss.utils.CommonUtils;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Server controller
 */
class CBSecurityController implements DBWSecurityController {

    private static final Log log = Log.getLog(CBSecurityController.class);

    private static final String CHAR_BOOL_TRUE = "Y";
    private static final String CHAR_BOOL_FALSE = "N";

    private static final String SUBJECT_USER = "U";
    private static final String SUBJECT_ROLE = "R";

    private final CBDatabase database;

    CBSecurityController(CBDatabase database) {
        this.database = database;
    }

    private boolean isSubjectExists(String subjectId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement("SELECT 1 FROM CB_AUTH_SUBJECT WHERE SUBJECT_ID=?")) {
                dbStat.setString(1, subjectId);
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    return dbResult.next();
                }
            }
        } catch (SQLException e) {
            throw new DBCException("Error while searching credentials", e);
        }
    }

    ///////////////////////////////////////////
    // Users

    @Override
    public void createUser(WebUser user) throws DBCException {
        if (isSubjectExists(user.getUserId())) {
            throw new DBCException("User or role '" + user.getUserId() + "' already exists");
        }
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                createAuthSubject(dbCon, user.getUserId(), SUBJECT_USER);
                try (PreparedStatement dbStat = dbCon.prepareStatement("INSERT INTO CB_USER(USER_ID,IS_ACTIVE,CREATE_TIME) VALUES(?,?,?)")) {
                    dbStat.setString(1, user.getUserId());
                    dbStat.setString(2, CHAR_BOOL_TRUE);
                    dbStat.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                    dbStat.execute();
                }
                Map<String, String> metaParameters = user.getMetaParameters();
                if (!CommonUtils.isEmpty(metaParameters)) {
                    try (PreparedStatement dbStat = dbCon.prepareStatement("INSERT INTO CB_USER_META(USER_ID,META_ID,META_VALUE) VALUES(?,?,?)")) {
                        dbStat.setString(1, user.getUserId());
                        for (Map.Entry<String, String> mp : metaParameters.entrySet()) {
                            dbStat.setString(2, mp.getKey());
                            dbStat.setString(3, mp.getValue());
                            dbStat.execute();
                        }
                    }
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error saving user in database", e);
        }
    }

    @Override
    public void deleteUser(String userId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                deleteAuthSubject(dbCon, userId);
                JDBCUtils.executeStatement(dbCon, "DELETE FROM CB_USER WHERE USER_ID=?", userId);
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error deleting user from database", e);
        }
    }

    @Override
    public void setUserRoles(String userId, String[] roleIds, String grantorId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                JDBCUtils.executeStatement(dbCon, "DELETE FROM CB_USER_ROLE WHERE USER_ID=?", userId);
                if (!ArrayUtils.isEmpty(roleIds)) {
                    try (PreparedStatement dbStat = dbCon.prepareStatement("INSERT INTO CB_USER_ROLE(USER_ID,ROLE_ID,GRANT_TIME,GRANTED_BY) VALUES(?,?,?,?)")) {
                        for (String roleId : roleIds) {
                            dbStat.setString(1, userId);
                            dbStat.setString(2, roleId);
                            dbStat.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                            dbStat.setString(4, grantorId);
                            dbStat.execute();
                        }
                    }
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error saving user roles in database", e);
        }
    }

    @NotNull
    @Override
    public WebRole[] getUserRoles(String userId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement(
                "SELECT R.* FROM CB_USER_ROLE UR,CB_ROLE R " +
                "WHERE UR.USER_ID=? AND UR.ROLE_ID=R.ROLE_ID")) {
                dbStat.setString(1, userId);
                List<WebRole> roles = new ArrayList<>();
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        roles.add(fetchRole(dbResult));
                    }
                }
                return roles.toArray(new WebRole[0]);
            }
        } catch (SQLException e) {
            throw new DBCException("Error while reading user roles", e);
        }
    }

    @Override
    public WebUser getUserById(String userId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            WebUser user;
            try (PreparedStatement dbStat = dbCon.prepareStatement("SELECT * FROM CB_USER WHERE USER_ID=?")) {
                dbStat.setString(1, userId);
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        user = new WebUser(dbResult.getString(1));
                    } else {
                        return null;
                    }
                }
            }
            try (PreparedStatement dbStat = dbCon.prepareStatement("SELECT META_ID,META_VALUE FROM CB_USER_META WHERE USER_ID=?")) {
                dbStat.setString(1, userId);
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        user.setMetaParameter(
                            dbResult.getString(1),
                            dbResult.getString(2)
                        );
                    }
                }
            }
            return user;
        } catch (SQLException e) {
            throw new DBCException("Error while searching credentials", e);
        }
    }

    @NotNull
    @Override
    public WebUser[] findUsers(String userNameMask) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            Map<String, WebUser> result = new LinkedHashMap<>();
            // Read users
            try (PreparedStatement dbStat = dbCon.prepareStatement("SELECT * FROM CB_USER" +
                (CommonUtils.isEmpty(userNameMask) ? "\nORDER BY USER_ID" : " WHERE USER_ID=?"))) {
                if (!CommonUtils.isEmpty(userNameMask)) {
                    dbStat.setString(1, userNameMask);
                }
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        String userId = dbResult.getString(1);
                        result.put(userId, new WebUser(userId));
                    }
                }
            }
            // Read metas
            try (PreparedStatement dbStat = dbCon.prepareStatement("SELECT USER_ID,META_ID,META_VALUE FROM CB_USER_META" +
                (CommonUtils.isEmpty(userNameMask) ? "" : " WHERE USER_ID=?"))) {
                if (!CommonUtils.isEmpty(userNameMask)) {
                    dbStat.setString(1, userNameMask);
                }
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        String userId = dbResult.getString(1);
                        WebUser user = result.get(userId);
                        if (user != null) {
                            user.setMetaParameter(
                                dbResult.getString(2),
                                dbResult.getString(3)
                            );
                        }
                    }
                }
            }
            return result.values().toArray(new WebUser[0]);
        } catch (SQLException e) {
            throw new DBCException("Error while loading users", e);
        }
    }

    @Override
    public void setUserMeta(String userId, Map<String, Object> metaParameters) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                // Delete old metas
                try (PreparedStatement dbStat = dbCon.prepareStatement("DELETE FROM CB_USER_META WHERE USER_ID=?")) {
                    dbStat.setString(1, userId);
                    dbStat.execute();
                }
                if (!metaParameters.isEmpty()) {
                    // Insert new metas
                    try (PreparedStatement dbStat = dbCon.prepareStatement("INSERT INTO CB_USER_META(USER_ID,META_ID,META_VALUE) VALUES(?,?,?)")) {
                        dbStat.setString(1, userId);
                        for (Map.Entry<String, Object> mpe : metaParameters.entrySet()) {
                            dbStat.setString(2, mpe.getKey());
                            dbStat.setString(3, CommonUtils.toString(mpe.getValue()));
                            dbStat.execute();
                        }
                    }
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error while loading users", e);
        }
    }

    @Override
    public Map<String, Object> getUserParameters(String userId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            Map<String, Object> result = new LinkedHashMap<>();
            // Read users
            try (PreparedStatement dbStat = dbCon.prepareStatement("SELECT * FROM CB_USER_PARAMETERS  WHERE USER_ID=?")) {
                dbStat.setString(1, userId);
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        result.put(
                            dbResult.getString(2),
                            dbResult.getString(3));
                    }
                }
            }
            return result;
        } catch (SQLException e) {
            throw new DBCException("Error while loading users", e);
        }
    }

    @Override
    public void setUserParameter(String userId, String name, Object value) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                if (value == null) {
                    // Delete old metas
                    try (PreparedStatement dbStat = dbCon.prepareStatement("DELETE FROM CB_USER_PARAMETERS WHERE USER_ID=? AND PARAM_ID=?")) {
                        dbStat.setString(1, userId);
                        dbStat.setString(2, name);
                        dbStat.execute();
                    }
                } else {
                    // Update/Insert parameter
                    boolean updated;
                    try (PreparedStatement dbStat = dbCon.prepareStatement("UPDATE CB_USER_PARAMETERS SET PARAM_VALUE=? WHERE USER_ID=? AND PARAM_ID=?")) {
                        dbStat.setString(1, CommonUtils.toString(value));
                        dbStat.setString(2, userId);
                        dbStat.setString(3, name);
                        updated = dbStat.executeUpdate() > 0;
                    }
                    if (!updated) {
                        try (PreparedStatement dbStat = dbCon.prepareStatement("INSERT INTO CB_USER_PARAMETERS (USER_ID,PARAM_ID,PARAM_VALUE) VALUES(?,?,?)")) {
                            dbStat.setString(1, userId);
                            dbStat.setString(2, name);
                            dbStat.setString(3, CommonUtils.toString(value));
                            dbStat.executeUpdate();
                        }
                    }
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error while updating user configuration", e);
        }
    }

    ///////////////////////////////////////////
    // Credentials

    private static DBAAuthCredentialsProfile getCredentialProfileByParameters(DBAAuthProviderDescriptor authProvider, Set<String> keySet) {
        List<DBAAuthCredentialsProfile> credentialProfiles = authProvider.getCredentialProfiles();
        if (credentialProfiles.size() > 1) {
            for (DBAAuthCredentialsProfile profile : credentialProfiles) {
                if (profile.getCredentialParameters().size() == keySet.size()) {
                    boolean matches = true;
                    for (String paramName : keySet) {
                        if (profile.getCredentialParameter(paramName) == null) {
                            matches = false;
                            break;
                        }
                    }
                    if (matches) {
                        return profile;
                    }
                }
            }
        }
        return credentialProfiles.get(0);
    }

    @Override
    public void setUserCredentials(String userId, DBAAuthProviderDescriptor authProvider, Map<String, Object> credentials) throws DBCException {
        List<String[]> transformedCredentials;
        try {
            DBAAuthCredentialsProfile credProfile = getCredentialProfileByParameters(authProvider, credentials.keySet());
            transformedCredentials = credentials.entrySet().stream().map(cred -> {
                String propertyName = cred.getKey();
                AuthPropertyDescriptor property = credProfile.getCredentialParameter(propertyName);
                if (property == null) {
                    return null;
                }
                String encodedValue = CommonUtils.toString(cred.getValue());
                encodedValue = property.getEncryption().encrypt(userId, encodedValue);
                return new String[] {propertyName, encodedValue };
            }).collect(Collectors.toList());
        } catch (Exception e) {
            throw new DBCException(e.getMessage(), e);
        }
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                JDBCUtils.executeStatement(dbCon, "DELETE FROM CB_USER_CREDENTIALS WHERE USER_ID=? AND PROVIDER_ID=?", userId, authProvider.getId());
                if (!CommonUtils.isEmpty(credentials)) {
                    try (PreparedStatement dbStat = dbCon.prepareStatement("INSERT INTO CB_USER_CREDENTIALS(USER_ID,PROVIDER_ID,CRED_ID,CRED_VALUE) VALUES(?,?,?,?)")) {
                        for (String[] cred : transformedCredentials) {
                            if (cred == null) {
                                continue;
                            }
                            dbStat.setString(1, userId);
                            dbStat.setString(2, authProvider.getId());
                            dbStat.setString(3, cred[0]);
                            dbStat.setString(4, cred[1]);
                            dbStat.execute();
                        }
                    }
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error saving user credentials in database", e);
        }
    }

    @Nullable
    @Override
    public String getUserByCredentials(DBAAuthProviderDescriptor authProvider, Map<String, Object> authParameters) throws DBCException {
        Map<String, Object> identCredentials = new LinkedHashMap<>();
        for (AuthPropertyDescriptor prop : authProvider.getCredentialParameters(authParameters.keySet())) {
            if (prop.isIdentifying()) {
                String propId = CommonUtils.toString(prop.getId());
                Object paramValue = authParameters.get(propId);
                if (paramValue == null) {
                    throw new DBCException("Authentication parameter '" + prop.getId() + "' is missing");
                }
                if (prop.getEncryption() == AuthPropertyEncryption.hash) {
                    throw new DBCException("Hash encryption can't be used in identifying credentials");
                }
                identCredentials.put(propId, paramValue);
            }
        }
        if (identCredentials.isEmpty()) {
            throw new DBCException("No identifying credentials in provider '" + authProvider.getId() + "'");
        }
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT U.USER_ID,U.IS_ACTIVE FROM CB_USER U,CB_USER_CREDENTIALS UC\n");
        for (int joinNum = 0; joinNum < identCredentials.size() - 1; joinNum++) {
            sql.append(",CB_USER_CREDENTIALS UC").append(joinNum + 2);
        }
        sql.append("WHERE U.USER_ID=UC.USER_ID AND UC.PROVIDER_ID=? AND UC.CRED_ID=? AND UC.CRED_VALUE=?");
        for (int joinNum = 0; joinNum < identCredentials.size() - 1; joinNum++) {
            String joinAlias = "UC" + (joinNum + 2);
            sql.append(" AND ")
                .append(joinAlias).append(".USER_ID=UC.USER_ID")
                .append(joinAlias).append(".PROVIDER_ID=UC.PROVIDER_ID AND ")
                .append(joinAlias).append("CRED_ID=? AND ")
                .append(joinAlias).append("CRED_VALUE=?");
        }
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement(sql.toString())) {
                dbStat.setString(1, authProvider.getId());
                int param = 2;
                for (Map.Entry<String, Object> credEntry : identCredentials.entrySet()) {
                    dbStat.setString(param++, credEntry.getKey());
                    dbStat.setString(param++, CommonUtils.toString(credEntry.getValue()));
                }

                try (ResultSet dbResult = dbStat.executeQuery()) {
                    String userId = null;
                    boolean isActive = false;
                    while (dbResult.next()) {
                        String credUserId = dbResult.getString(1);
                        isActive = CHAR_BOOL_TRUE.equals(dbResult.getString(2));
                        if (userId == null) {
                            userId = credUserId;
                        } else if (!userId.equals(credUserId)) {
                            log.error("Multiple users associated with the same credentials! " + credUserId + ", " + userId);
                        }
                    }

                    if (userId != null && !isActive) {
                        throw new DBCException("User account is locked");
                    }

                    return userId;
                }
            }
        } catch (SQLException e) {
            throw new DBCException("Error while searching credentials", e);
        }
    }

    @Override
    public Map<String, Object> getUserCredentials(String userId, DBAAuthProviderDescriptor authProvider) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement(
                "SELECT CRED_ID,CRED_VALUE FROM CB_USER_CREDENTIALS\n" +
                    "WHERE USER_ID=? AND PROVIDER_ID=?")) {
                dbStat.setString(1, userId);

                dbStat.setString(2, authProvider.getId());

                try (ResultSet dbResult = dbStat.executeQuery()) {
                    Map<String, Object> credentials = new LinkedHashMap<>();

                    while (dbResult.next()) {
                        credentials.put(dbResult.getString(1), dbResult.getString(2));
                    }

                    return credentials;
                }
            }
        } catch (SQLException e) {
            throw new DBCException("Error saving role in database", e);
        }
    }

    @Override
    public String[] getUserLinkedProviders(String userId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement(
                "SELECT DISTINCT PROVIDER_ID FROM CB_USER_CREDENTIALS\n" +
                    "WHERE USER_ID=?")) {
                dbStat.setString(1, userId);

                try (ResultSet dbResult = dbStat.executeQuery()) {
                    List<String> providerIds = new ArrayList<>();

                    while (dbResult.next()) {
                        providerIds.add(dbResult.getString(1));
                    }

                    return providerIds.toArray(new String[0]);
                }
            }
        } catch (SQLException e) {
            throw new DBCException("Error saving role in database", e);
        }
    }

    ///////////////////////////////////////////
    // Roles

    @NotNull
    @Override
    public WebRole[] readAllRoles() throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            Map<String, WebRole> roles = new LinkedHashMap<>();
            try (Statement dbStat = dbCon.createStatement()) {
                try (ResultSet dbResult = dbStat.executeQuery("SELECT * FROM CB_ROLE ORDER BY ROLE_ID")) {
                    while (dbResult.next()) {
                        WebRole role = fetchRole(dbResult);
                        roles.put(role.getRoleId(), role);
                    }
                }
                try (ResultSet dbResult = dbStat.executeQuery("SELECT SUBJECT_ID,PERMISSION_ID\n" +
                    "FROM CB_AUTH_PERMISSIONS AP,CB_ROLE R\n" +
                    "WHERE AP.SUBJECT_ID=R.ROLE_ID\n")) {
                    while (dbResult.next()) {
                        WebRole role = roles.get(dbResult.getString(1));
                        if (role != null) {
                            role.addPermission(dbResult.getString(2));
                        }
                    }
                }
            }
            return roles.values().toArray(new WebRole[0]);
        } catch (SQLException e) {
            throw new DBCException("Error reading roles from database", e);
        }
    }

    @Override
    public WebRole findRole(String roleId) throws DBCException {
        return Arrays.stream(readAllRoles())
            .filter(r -> r.getRoleId().equals(roleId))
            .findFirst().orElse(null);
    }

    @NotNull
    @Override
    public String[] getRoleSubjects(String roleId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement(
                "SELECT USER_ID FROM CB_USER_ROLE WHERE ROLE_ID=?")) {
                dbStat.setString(1, roleId);
                List<String> subjects = new ArrayList<>();
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        subjects.add(dbResult.getString(1));
                    }
                }
                return subjects.toArray(new String[0]);
            }
        } catch (SQLException e) {
            throw new DBCException("Error while reading role subjects", e);
        }
    }

    @NotNull
    private WebRole fetchRole(ResultSet dbResult) throws SQLException {
        WebRole role = new WebRole(dbResult.getString("ROLE_ID"));
        role.setName(dbResult.getString("ROLE_NAME"));
        role.setDescription(dbResult.getString("ROLE_DESCRIPTION"));
        return role;
    }

    @Override
    public void createRole(WebRole role, String grantor) throws DBCException {
        if (isSubjectExists(role.getRoleId())) {
            throw new DBCException("User or role '" + role.getRoleId() + "' already exists");
        }
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                createAuthSubject(dbCon, role.getRoleId(), SUBJECT_ROLE);
                try (PreparedStatement dbStat = dbCon.prepareStatement(
                    "INSERT INTO CB_ROLE(ROLE_ID,ROLE_NAME,ROLE_DESCRIPTION,CREATE_TIME) VALUES(?,?,?,?)")) {
                    dbStat.setString(1, role.getRoleId());
                    dbStat.setString(2, CommonUtils.notEmpty(role.getName()));
                    dbStat.setString(3, CommonUtils.notEmpty(role.getDescription()));
                    dbStat.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
                    dbStat.execute();
                }

                insertPermissions(dbCon, role.getRoleId(),
                    new String[] {DBWConstants.PERMISSION_PUBLIC} , grantor);

                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error saving role in database", e);
        }
    }

    @Override
    public void updateRole(WebRole role) throws DBCException {
        if (!isSubjectExists(role.getRoleId())) {
            throw new DBCException("Role '" + role.getRoleId() + "' doesn't exists");
        }
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                try (PreparedStatement dbStat = dbCon.prepareStatement(
                    "UPDATE CB_ROLE SET ROLE_NAME=?,ROLE_DESCRIPTION=? WHERE ROLE_ID=?")) {
                    dbStat.setString(1, CommonUtils.notEmpty(role.getName()));
                    dbStat.setString(2, CommonUtils.notEmpty(role.getDescription()));
                    dbStat.setString(3, role.getRoleId());
                    if (dbStat.executeUpdate() <= 0) {
                        throw new DBCException("Role '" + role.getRoleId() + "' doesn't exist");
                    }
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error updating role info in database", e);
        }
    }

    @Override
    public void deleteRole(String roleId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement(
                "SELECT COUNT(*) FROM CB_USER_ROLE WHERE ROLE_ID=?")) {
                dbStat.setString(1, roleId);
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        int userCount = dbResult.getInt(1);
                        if (userCount > 0) {
                            throw new DBCException("Role can't be deleted. There are " + userCount + " user(s) who have this role. Un-assign role first.");
                        }
                    }
                }
            }

            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                deleteAuthSubject(dbCon, roleId);
                try (PreparedStatement dbStat = dbCon.prepareStatement(
                    "DELETE FROM CB_ROLE WHERE ROLE_ID=?")) {
                    dbStat.setString(1, roleId);
                    dbStat.execute();
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error deleting role from database", e);
        }
    }

    ///////////////////////////////////////////
    // Permissions

    @Override
    public void setSubjectPermissions(String subjectId, String[] permissionIds, String grantorId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                JDBCUtils.executeStatement(dbCon, "DELETE FROM CB_AUTH_PERMISSIONS WHERE SUBJECT_ID=?", subjectId);
                insertPermissions(dbCon, subjectId, permissionIds, grantorId);
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error saving role permissions in database", e);
        }
    }

    private void insertPermissions(Connection dbCon, String subjectId, String[] permissionIds, String grantorId) throws SQLException {
        if (!ArrayUtils.isEmpty(permissionIds)) {
            try (PreparedStatement dbStat = dbCon.prepareStatement("INSERT INTO CB_AUTH_PERMISSIONS(SUBJECT_ID,PERMISSION_ID,GRANT_TIME,GRANTED_BY) VALUES(?,?,?,?)")) {
                for (String permission : permissionIds) {
                    dbStat.setString(1, subjectId);
                    dbStat.setString(2, permission);
                    dbStat.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
                    dbStat.setString(4, grantorId);
                    dbStat.execute();
                }
            }
        }
    }

    @NotNull
    @Override
    public Set<String> getSubjectPermissions(String subjectId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            Set<String> permissions = new HashSet<>();
            try (PreparedStatement dbStat = dbCon.prepareStatement("SELECT PERMISSION_ID FROM CB_AUTH_PERMISSIONS WHERE SUBJECT_ID=?")) {
                dbStat.setString(1, subjectId);
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        permissions.add(dbResult.getString(1));
                    }
                }
            }
            return permissions;
        } catch (SQLException e) {
            throw new DBCException("Error saving role in database", e);
        }
    }

    @NotNull
    @Override
    public Set<String> getUserPermissions(String userId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            Set<String> permissions = new HashSet<>();
            try (PreparedStatement dbStat = dbCon.prepareStatement(
                "SELECT DISTINCT AP.PERMISSION_ID FROM CB_AUTH_PERMISSIONS AP,CB_USER_ROLE UR\n" +
                    "WHERE UR.ROLE_ID=AP.SUBJECT_ID AND UR.USER_ID=?")) {
                dbStat.setString(1, userId);
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        permissions.add(dbResult.getString(1));
                    }
                }
            }
            try (PreparedStatement dbStat = dbCon.prepareStatement("SELECT PERMISSION_ID FROM CB_AUTH_PERMISSIONS WHERE SUBJECT_ID=?")) {
                dbStat.setString(1, userId);
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    while (dbResult.next()) {
                        permissions.add(dbResult.getString(1));
                    }
                }
            }
            permissions.addAll(getSubjectPermissions(userId));
            return permissions;
        } catch (SQLException e) {
            throw new DBCException("Error reading user permissions", e);
        }
    }

    ///////////////////////////////////////////
    // Sessions

    @Override
    public boolean isSessionPersisted(String id) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement(
                "SELECT 1 FROM CB_SESSION WHERE SESSION_ID=?")) {
                dbStat.setString(1, id);
                try (ResultSet dbResult = dbStat.executeQuery()) {
                    if (dbResult.next()) {
                        return true;
                    }
                }
            }
            return false;
        } catch (SQLException e) {
            throw new DBCException("Error reading session state", e);
        }
    }

    @Override
    public void createSession(WebSession session) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement(
                "INSERT INTO CB_SESSION(SESSION_ID,USER_ID,CREATE_TIME,LAST_ACCESS_TIME,LAST_ACCESS_REMOTE_ADDRESS,LAST_ACCESS_USER_AGENT,LAST_ACCESS_INSTANCE_ID) " +
                    "VALUES(?,?,?,?,?,?,?)")) {
                dbStat.setString(1, session.getSessionId());
                WebUser user = session.getUser();
                if (user == null) {
                    dbStat.setNull(2, Types.VARCHAR);
                } else {
                    dbStat.setString(2, user.getUserId());
                }
                Timestamp currentTS = new Timestamp(System.currentTimeMillis());
                dbStat.setTimestamp(3, currentTS);
                dbStat.setTimestamp(4, currentTS);
                if (session.getLastRemoteAddr() != null) {
                    dbStat.setString(5, session.getLastRemoteAddr());
                } else {
                    dbStat.setNull(5, Types.VARCHAR);
                }
                if (session.getLastRemoteUserAgent() != null) {
                    dbStat.setString(6, session.getLastRemoteUserAgent());
                } else {
                    dbStat.setNull(6, Types.VARCHAR);
                }
                dbStat.setString(7, database.getInstanceId());
                dbStat.execute();
            }
        } catch (SQLException e) {
            throw new DBCException("Error creating session in database", e);
        }
    }

    @Override
    public void updateSession(WebSession session) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (PreparedStatement dbStat = dbCon.prepareStatement(
                "UPDATE CB_SESSION SET USER_ID=?,LAST_ACCESS_TIME=?,LAST_ACCESS_REMOTE_ADDRESS=?,LAST_ACCESS_USER_AGENT=?,LAST_ACCESS_INSTANCE_ID=? WHERE SESSION_ID=?")) {
                WebUser user = session.getUser();
                if (user == null) {
                    dbStat.setNull(1, Types.VARCHAR);
                } else {
                    dbStat.setString(1, user.getUserId());
                }
                dbStat.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                if (session.getLastRemoteAddr() != null) {
                    dbStat.setString(3, CommonUtils.truncateString(session.getLastRemoteAddr(), 128));
                } else {
                    dbStat.setNull(3, Types.VARCHAR);
                }
                if (session.getLastRemoteUserAgent() != null) {
                    dbStat.setString(4, CommonUtils.truncateString(session.getLastRemoteUserAgent(), 255));
                } else {
                    dbStat.setNull(4, Types.VARCHAR);
                }
                dbStat.setString(5, database.getInstanceId());

                dbStat.setString(6, session.getSessionId());
                if (dbStat.executeUpdate() <= 0) {
                    throw new DBCException("Session not exists in database");
                }
            }
        } catch (SQLException e) {
            throw new DBCException("Error updating session in database", e);
        }
    }

    ///////////////////////////////////////////
    // Access management

    @NotNull
    @Override
    public DBWConnectionGrant[] getSubjectConnectionAccess(@NotNull String[] subjectIds) throws DBCException {
        if (subjectIds.length == 0) {
            return new DBWConnectionGrant[0];
        }
        List<String> allSubjects = new ArrayList<>();
        Collections.addAll(allSubjects, subjectIds);

        try (Connection dbCon = database.openConnection()) {
            {
                StringBuilder sql = new StringBuilder("SELECT ROLE_ID FROM CB_USER_ROLE WHERE USER_ID IN (");
                appendStringParameters(sql, subjectIds);
                sql.append(")");

                try (Statement dbStat = dbCon.createStatement()) {
                    try (ResultSet dbResult = dbStat.executeQuery(sql.toString())) {
                        while (dbResult.next()) {
                            allSubjects.add(dbResult.getString(1));
                        }
                    }
                }
            }
            {
                StringBuilder sql = new StringBuilder("SELECT DA.DATASOURCE_ID,DA.SUBJECT_ID,S.SUBJECT_TYPE FROM CB_DATASOURCE_ACCESS DA,\n" +
                    "CB_AUTH_SUBJECT S\nWHERE S.SUBJECT_ID = DA.SUBJECT_ID AND DA.SUBJECT_ID IN (");
                appendStringParameters(sql, allSubjects.toArray(new String[0]));
                sql.append(")");

                if (allSubjects.isEmpty()) {
                    return new DBWConnectionGrant[0];
                }
                try (Statement dbStat = dbCon.createStatement()) {
                    List<DBWConnectionGrant> result = new ArrayList<>();
                    try (ResultSet dbResult = dbStat.executeQuery(sql.toString())) {
                        while (dbResult.next()) {
                            result.add(new DBWConnectionGrant(
                                dbResult.getString(1),
                                dbResult.getString(2),
                                DBWSecuritySubjectType.fromCode(dbResult.getString(3))));
                        }
                    }
                    return result.toArray(new DBWConnectionGrant[0]);
                }
            }
        } catch (SQLException e) {
            throw new DBCException("Error reading datasource access", e);
        }
    }

    @Override
    public void setSubjectConnectionAccess(@NotNull String subjectId, @NotNull String[] connectionIds, String grantorId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                JDBCUtils.executeStatement(dbCon,
                    "DELETE FROM CB_DATASOURCE_ACCESS WHERE SUBJECT_ID=?", subjectId);
                if (!ArrayUtils.isEmpty(connectionIds)) {
                    try (PreparedStatement dbStat = dbCon.prepareStatement(
                        "INSERT INTO CB_DATASOURCE_ACCESS(SUBJECT_ID,GRANT_TIME,GRANTED_BY,DATASOURCE_ID) VALUES(?,?,?,?)")) {
                        dbStat.setString(1, subjectId);
                        dbStat.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                        dbStat.setString(3, grantorId);
                        for (String connectionId : connectionIds) {
                            dbStat.setString(4, connectionId);
                            dbStat.execute();
                        }
                    }
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error granting datasource access", e);
        }
    }

    @NotNull
    @Override
    public DBWConnectionGrant[] getConnectionSubjectAccess(String connectionId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            {
                try (PreparedStatement dbStat = dbCon.prepareStatement(
                    "SELECT DA.SUBJECT_ID,S.SUBJECT_TYPE\n" +
                        "FROM CB_DATASOURCE_ACCESS DA,CB_AUTH_SUBJECT S\n" +
                        "WHERE S.SUBJECT_ID = DA.SUBJECT_ID AND DA.DATASOURCE_ID=?")) {
                    dbStat.setString(1, connectionId);
                    List<DBWConnectionGrant> result = new ArrayList<>();
                    try (ResultSet dbResult = dbStat.executeQuery()) {
                        while (dbResult.next()) {
                            result.add(new DBWConnectionGrant(
                                connectionId,
                                dbResult.getString(1),
                                DBWSecuritySubjectType.fromCode(dbResult.getString(2))));
                        }
                    }
                    return result.toArray(new DBWConnectionGrant[0]);
                }
            }
        } catch (SQLException e) {
            throw new DBCException("Error reading datasource access", e);
        }
    }

    @Override
    public void setConnectionSubjectAccess(@NotNull String connectionId, @Nullable String[] subjects, @Nullable String grantorId) throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                // Delete all permissions
                JDBCUtils.executeStatement(dbCon,
                    "DELETE FROM CB_DATASOURCE_ACCESS WHERE DATASOURCE_ID=?", connectionId);
                if (!ArrayUtils.isEmpty(subjects)) {
                    try (PreparedStatement dbStat = dbCon.prepareStatement(
                        "INSERT INTO CB_DATASOURCE_ACCESS(DATASOURCE_ID,GRANT_TIME,GRANTED_BY,SUBJECT_ID) VALUES(?,?,?,?)")) {
                        dbStat.setString(1, connectionId);
                        dbStat.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                        dbStat.setString(3, grantorId);
                        for (String subject : subjects) {
                            dbStat.setString(4, subject);
                            dbStat.execute();
                        }
                    }
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error granting datasource access", e);
        }
    }

    private void appendStringParameters(StringBuilder sql, @NotNull String[] subjectIds) {
        for (int i = 0; i < subjectIds.length; i++) {
            String id = subjectIds[i];
            if (i > 0) sql.append(",");
            sql.append("'").append(id.replace("'", "''")).append("'");
        }
    }

    ///////////////////////////////////////////
    // Utils

    void initializeMetaInformation() throws DBCException {
        try (Connection dbCon = database.openConnection()) {
            try (JDBCTransaction txn = new JDBCTransaction(dbCon)) {
                Set<String> registeredProviders = new HashSet<>();
                try (PreparedStatement dbStat = dbCon.prepareStatement(
                    "SELECT PROVIDER_ID FROM CB_AUTH_PROVIDER")) {
                    try (ResultSet dbResult = dbStat.executeQuery()) {
                        while (dbResult.next()) {
                            registeredProviders.add(dbResult.getString(1));
                        }
                    }
                }
                try (PreparedStatement dbStat = dbCon.prepareStatement(
                    "INSERT INTO CB_AUTH_PROVIDER(PROVIDER_ID,IS_ENABLED) VALUES(?,'Y')")) {
                    for (AuthProviderDescriptor authProvider : AuthProviderRegistry.getInstance().getAuthProviders()) {
                        if (!registeredProviders.contains(authProvider.getId())) {
                            dbStat.setString(1, authProvider.getId());
                            dbStat.executeUpdate();
                            log.debug("Auth provider '" + authProvider.getId() + "' registered");
                        }
                    }
                }
                txn.commit();
            }
        } catch (SQLException e) {
            throw new DBCException("Error reading session state", e);
        }
    }

    private void createAuthSubject(Connection dbCon, String subjectId, String subjectType) throws SQLException {
        try (PreparedStatement dbStat = dbCon.prepareStatement("INSERT INTO CB_AUTH_SUBJECT(SUBJECT_ID,SUBJECT_TYPE) VALUES(?,?)")) {
            dbStat.setString(1, subjectId);
            dbStat.setString(2, subjectType);
            dbStat.execute();
        }
    }

    private void deleteAuthSubject(Connection dbCon, String subjectId) throws SQLException {
        try (PreparedStatement dbStat = dbCon.prepareStatement("DELETE FROM CB_AUTH_SUBJECT WHERE SUBJECT_ID=?")) {
            dbStat.setString(1, subjectId);
            dbStat.execute();
        }
    }

}
