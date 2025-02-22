/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import type { IResultSetColumnKey, IResultSetRowKey } from './IResultSetDataKey';

export const ResultSetDataKeysUtils = {
  serialize(key: IResultSetColumnKey | IResultSetRowKey): string {
    let base = `${key.index}`;

    if ('key' in key) {
      base += `_${key.key}`;
    }

    return base;
  },
  isEqual<T extends IResultSetColumnKey | IResultSetRowKey>(a: T, b: T): boolean {
    if (a.index !== b.index) {
      return false;
    }

    const keyA = 'key' in a;
    const keyB = 'key' in b;

    if (keyA !== keyB) {
      return false;
    }

    if (keyA && (a as IResultSetRowKey).key !== (b as IResultSetRowKey).key) {
      return false;
    }

    return true;
  },
};
