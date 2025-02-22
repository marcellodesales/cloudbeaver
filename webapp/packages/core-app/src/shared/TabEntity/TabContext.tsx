/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import type { TabPanelElement } from '@cloudbeaver/core-ui';
import { AppContext, IServiceInjector } from '@cloudbeaver/core-di';

export function TabContext(injector: IServiceInjector, Panel: TabPanelElement) {
  return (
    <AppContext app={injector}>
      <Panel />
    </AppContext>
  );
}
