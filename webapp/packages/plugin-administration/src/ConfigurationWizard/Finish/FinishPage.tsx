/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import { observer } from 'mobx-react-lite';
import styled, { use, css } from 'reshadow';

import { ConfigurationWizardService } from '@cloudbeaver/core-administration';
import { Button, useFocus } from '@cloudbeaver/core-blocks';
import { useController } from '@cloudbeaver/core-di';
import { useTranslate } from '@cloudbeaver/core-localization';
import { useStyles, composes } from '@cloudbeaver/core-theming';

const styles = composes(
  css`
    layout-grid-cell {
      composes: theme-background-surface theme-text-on-surface from global;
    }

    layout-grid-cell {
      composes: theme-border-color-background from global;
    }
  `,
  css`
    layout-grid {
      width: 100%;
      flex: 1;
    }

    layout-grid-inner {
      min-height: 100%;
    }

    layout-grid-cell {
      position: relative;
      border: solid 1px;
      padding: 16px 24px
    }

    p {
      line-height: 2;
      white-space: pre;
    }
  `
);

export const FinishPage = observer(function FinishPage() {
  const translate = useTranslate();
  const service = useController(ConfigurationWizardService);
  const [focus] = useFocus<HTMLDivElement>({
    focusFirstChild: true,
  });

  return styled(useStyles(styles))(
    <layout-grid as="div">
      <layout-grid-inner as="div">
        <layout-grid-cell ref={focus} as='div' {...use({ span: 12 })}>
          <h3>{translate('administration_configuration_wizard_finish_title')}</h3>
          <p>{translate('administration_configuration_wizard_finish_message')}</p>

          <Button
            type="button"
            mod={['unelevated']}
            onClick={() => service.next()}
          >
            {translate('ui_stepper_finish')}
          </Button>
        </layout-grid-cell>
      </layout-grid-inner>
    </layout-grid>
  );
});
