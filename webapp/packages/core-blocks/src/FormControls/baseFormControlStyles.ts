/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import { css } from 'reshadow';

import { composes } from '@cloudbeaver/core-theming';

export const baseFormControlStyles = composes(
  css`
  `,
  css`
    field {
      box-sizing: border-box;
      max-width: 100%;
      &[|small] {
        max-width: 250px;
      }
      &[|medium] {
        max-width: 450px;
      }
      &[|large] {
        max-width: 650px;
      }
    }

    field-label {
      box-sizing: border-box;
      overflow: hidden;
      white-space: nowrap;
      text-overflow: ellipsis;
    }

    field-description {
      composes: theme-typography--caption from global;
      padding-top: 4px;
    }

    input, textarea {
      font-size: 12px;
    }
  `
);

export const baseValidFormControlStyles = composes(
  css`
    field-description {
      composes: theme-text-text-hint-on-light from global;
    }
  `,
  css``
);

export const baseInvalidFormControlStyles = composes(
  css`
    field-description {
      composes: theme-text-negative from global;
    }
  `,
  css``
);
