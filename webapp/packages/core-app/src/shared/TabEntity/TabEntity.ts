/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import type { ITab } from '@cloudbeaver/core-ui';
import {
  Entity, isConstructor, ITypedConstructor, MixinProvider
} from '@cloudbeaver/core-di';

import { TabToken } from './TabToken';
import { TabViewModel } from './TabViewModel';

export class TabEntity extends Entity {
  constructor(tab: ITab | ITypedConstructor<ITab>,
    providers: Array<MixinProvider<any>> = []) {
    super(providers, isConstructor(tab) ? undefined : tab.tabId);
    this.addMixin(TabToken, tab);
    this.addMixin(TabViewModel);
  }

  getViewModel() {
    return this.getMixin(TabViewModel);
  }

  getTabModel() {
    return this.getMixin(TabToken);
  }
}
