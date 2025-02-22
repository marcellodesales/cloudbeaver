/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import { useObjectRef } from '@cloudbeaver/core-blocks';
import { DBDriver, isJDBCConnection } from '@cloudbeaver/core-connections';
import type { DatabaseAuthModel } from '@cloudbeaver/core-sdk';

import type { IConnectionFormState } from '../IConnectionFormProps';

const MAX_HOST_LENGTH = 20;

export function useOptions(state: IConnectionFormState) {
  const refObject = useObjectRef(() => ({
    prevName: null as string | null,
  }), {
    state,
  });

  return useObjectRef({
    updateNameTemplate(driver: DBDriver | undefined) {
      const {
        prevName,
        state: {
          config,
          info,
          mode,
        },
      } = refObject;

      const isAutoFill = config.name === prevName || prevName === null;

      if (mode === 'edit' || !isAutoFill) {
        return;
      }

      if (isJDBCConnection(driver, info)) {
        refObject.prevName = config.url || '';
        config.name = config.url || '';
        return;
      }

      if (!driver) {
        config.name = 'New connection';
        return;
      }

      let name = driver.name || '';
      if (config.host) {
        name += '@' + config.host.slice(0, MAX_HOST_LENGTH);
        if (config.port && config.port !== driver.defaultPort) {
          name += ':' + config.port;
        }
      }
      refObject.prevName = name;
      config.name = name;
    },
    setDefaults(driver: DBDriver | undefined, prevDriver: DBDriver | undefined) {
      const {
        state: {
          config,
          info,
        },
      } = refObject;

      if (info) {
        return;
      }
      
      if (!prevDriver || config.host === prevDriver.defaultServer) {
        config.host = driver?.defaultServer || 'localhost';
      }

      if (!prevDriver || config.port === prevDriver.defaultPort) {
        config.port = driver?.defaultPort;
      }

      if (!prevDriver || config.databaseName === prevDriver.defaultDatabase) {
        config.databaseName = driver?.defaultDatabase;
      }

      if (!prevDriver || config.url === prevDriver.sampleURL) {
        config.url = driver?.sampleURL;
      }

      this.updateNameTemplate(driver);

      if (driver?.id !== prevDriver?.id) {
        for (const property of Object.keys(config.credentials)) {
          delete config.credentials[property];
        }

        for (const property of Object.keys(config.providerProperties)) {
          delete config.providerProperties[property];
        }

        config.authModelId = driver?.defaultAuthModel;
      }
      
    },
    setAuthModel(model: DatabaseAuthModel) {
      // const {
      //   props: {
      //     data: { info, config },
      //   },
      // } = refObject.current;

      // config.credentials = {};
    },
  });
}
