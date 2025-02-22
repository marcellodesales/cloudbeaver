/*
 * CloudBeaver - Cloud Database Manager
 * Copyright (C) 2020-2022 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0.
 * you may not use this file except in compliance with the License.
 */

import { observer } from 'mobx-react-lite';
import { useContext } from 'react';
import styled from 'reshadow';

import { TreeNodeNestedMessage, TREE_NODE_STYLES } from '@cloudbeaver/core-blocks';
import { useService } from '@cloudbeaver/core-di';
import { useStyles } from '@cloudbeaver/core-theming';

import { NavNodeInfoResource } from '../../shared/NodesManager/NavNodeInfoResource';
import type { NavTreeNodeComponent } from '../NavigationNodeComponent';
import { TreeContext } from '../TreeContext';
import { NavigationNode } from './NavigationNode';

export const NavigationNodeElement: NavTreeNodeComponent = observer(function NavigationNodeElement({
  nodeId,
  path,
  expanded,
}) {
  const context = useContext(TreeContext);
  const navNodeInfoResource = useService(NavNodeInfoResource);
  const styles = useStyles(TREE_NODE_STYLES);

  if (context?.tree.renderers) {
    for (const renderer of context.tree.renderers) {
      const CustomRenderer = renderer(nodeId);

      if (CustomRenderer) {
        return <CustomRenderer nodeId={nodeId} expanded={expanded} component={NavigationNodeElement} />;
      }
    }
  }

  const node = navNodeInfoResource.get(nodeId);

  if (!node) {
    return styled(styles)(<TreeNodeNestedMessage>Node not found</TreeNodeNestedMessage>);
  }

  // TODO: after node update reference can be lost and NavigationNode skip update
  return <NavigationNode node={node} path={path} expanded={expanded} component={NavigationNodeElement} />;
});
