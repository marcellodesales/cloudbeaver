/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import { action, computed, observable, makeObservable } from 'mobx';

import type { ITab, ITabContainer } from '@cloudbeaver/core-ui';
import { Entity, IServiceInjector, MixinProvider } from '@cloudbeaver/core-di';

import { ITabContainerEntity, TabContainerToken } from './TabContainerToken';
import { TabEntity } from './TabEntity';

export class TabContainerEntity extends Entity implements ITabContainer, ITabContainerEntity {
  get tabs(): ITab[] {
    return this.tabsOrder.map(id => {
      const tabEntity = this.getTabEntity(id);
      return tabEntity.getViewModel();
    });
  }

  get currentTabId(): string | null {
    return this._activeTabId;
  }

  private _activeTabId: string | null = null;
  private tabsOrder = observable.array<string>();

  constructor(providers: Array<MixinProvider<any>>, id?: string) {
    super(providers, id);

    makeObservable<TabContainerEntity, '_activeTabId'>(this, {
      tabs: computed,
      _activeTabId: observable,
      addTabEntity: action,
      closeTab: action,
      activateTab: action,
      addTabsAfter: action,
    });

    this.addMixin(TabContainerToken, this);
  }

  addTabEntity(tabEntity: TabEntity, desiredTabPosition?: number) {
    this.addChild(tabEntity);
    if (desiredTabPosition === undefined || desiredTabPosition < 0) {
      this.tabsOrder.push(tabEntity.id);
    } else {
      this.tabsOrder.spliceWithArray(desiredTabPosition, 0, [tabEntity.id]);
    }
  }

  closeTab(tabId: string) {
    const tabModel = this.getTabEntity(tabId).getTabModel();
    if (tabModel.onClose) {
      tabModel.onClose();
    }
    this.removeChild(tabId);
    this.tabsOrder.remove(tabId);
  }

  activateTab(tabId: string | null) {
    if (tabId === null) {
      this._activeTabId = null;
      return;
    }
    const tabModel = this.getTabEntity(tabId).getTabModel();
    if (tabModel.onActivate) {
      tabModel.onActivate();
    }
    this._activeTabId = tabId;
  }

  getTabServiceInjector(tabId: string): IServiceInjector {
    const tabEntity = this.getTabEntity(tabId);
    return tabEntity.getServiceInjector();
  }

  addTabsAfter(tabEntities: TabEntity[], tabId: string) {
    tabEntities.forEach((tabEntity, index) => {
      const previousTabId = index === 0 ? tabId : tabEntities[index - 1].id;
      const previousTabIndex = this.tabsOrder.findIndex(id => id === previousTabId);
      this.addTabEntity(tabEntity, previousTabIndex);
    });
  }

  private getTabEntity(tabId: string): TabEntity {
    const entity = this.children.get(tabId);
    if (entity instanceof TabEntity) {
      return entity;
    }
    throw Error(`Tab entity is not found: ${tabId}`);
  }
}
