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
package io.cloudbeaver.auth.provider.local;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.auth.DBAAuthSpace;
import org.jkiss.dbeaver.model.auth.DBASession;
import org.jkiss.dbeaver.model.auth.DBASessionContext;
import org.jkiss.dbeaver.model.auth.DBASessionPrincipal;

/**
 * Local auth provider
 */
public class LocalAuthSession implements DBASession {
    public static final DBAAuthSpace LOCAL_AUTH_SPACE = new DBAAuthSpace() {
    };
    private final DBASession webSession;
    private final String userId;

    LocalAuthSession(DBASession webSession, String userId) {
        this.webSession = webSession;
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    @NotNull
    @Override
    public DBAAuthSpace getSessionSpace() {
        return LOCAL_AUTH_SPACE;
    }

    @NotNull
    @Override
    public DBASessionContext getSessionContext() {
        return webSession.getSessionContext();
    }

    @Override
    public DBASessionPrincipal getSessionPrincipal() {
        return webSession.getSessionPrincipal();
    }

    @NotNull
    @Override
    public String getSessionId() {
        return webSession.getSessionId();
    }

    @Override
    public boolean isApplicationSession() {
        return false;
    }

    @Nullable
    @Override
    public DBPProject getSingletonProject() {
        return webSession.getSingletonProject();
    }

}
