import { describe, it, expect, vi, beforeEach } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { Node } from '@xyflow/react';
import '../../i18n';
import en from '../../i18n/locales/en.json';
import { renderPage, TEST_PROJECT_ID } from '../../test/renderPage';
import type { TransformationResponse } from '../../types/api.types';

vi.mock('../../api/transformations.api', () => ({
  transformationsApi: { list: vi.fn(), create: vi.fn() },
}));
vi.mock('../../api/endpoints.api', () => ({
  endpointsApi: { list: vi.fn().mockResolvedValue([]), create: vi.fn() },
}));
vi.mock('../../api/subscriptions.api', () => ({
  subscriptionsApi: { list: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../api/apiKeys.api', () => ({
  apiKeysApi: { list: vi.fn().mockResolvedValue([]) },
}));
vi.mock('../../api/schemas.api', () => ({
  schemasApi: { listEventTypes: vi.fn().mockResolvedValue([]) },
}));

// CodeMirror does not run in jsdom.
vi.mock('../JsonEditor', () => ({
  default: ({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder?: string }) => (
    <textarea aria-label={placeholder ?? 'template'} value={value} onChange={(e) => onChange(e.target.value)} />
  ),
}));

import NodeConfigPanel from '../workflow/NodeConfigPanel';
import { transformationsApi } from '../../api/transformations.api';

const TRANSFORMATION: TransformationResponse = {
  id: 'transformation-1',
  projectId: TEST_PROJECT_ID,
  name: 'Flatten the customer',
  template: '{"email":"${$.customer.email}"}',
  version: 1,
  enabled: true,
  createdAt: new Date('2026-08-01T00:00:00Z').toISOString(),
  updatedAt: new Date('2026-08-01T00:00:00Z').toISOString(),
} as TransformationResponse;

function transformNode(data: Record<string, unknown> = {}): Node {
  return { id: 'node-1', type: 'transform', position: { x: 0, y: 0 }, data };
}

function renderPanel(node: Node, onUpdate = vi.fn()) {
  renderPage(<NodeConfigPanel node={node} onUpdate={onUpdate} onClose={vi.fn()} />, {
    path: '/projects/:projectId/workflows/:workflowId',
    initialEntry: `/projects/${TEST_PROJECT_ID}/workflows/workflow-1`,
  });
  return onUpdate;
}

const nodeConfig = en.workflows.nodeConfig;
const sourceToggle = () => screen.getByRole('group', { name: nodeConfig.transformSource });

describe('the transform node’s source', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(transformationsApi.list).mockResolvedValue([TRANSFORMATION]);
  });

  it('offers the project’s saved transformations by name, and creates nothing by being opened', async () => {
    renderPanel(transformNode({ transformationId: TRANSFORMATION.id }));

    expect(await screen.findByRole('option', { name: /Flatten the customer/ })).toBeInTheDocument();
    expect(transformationsApi.create).not.toHaveBeenCalled();
  });

  it('starts on the inline template when the node has no reference', async () => {
    renderPanel(transformNode({ template: '{"a":1}' }));

    await waitFor(() => expect(within(sourceToggle()).getAllByRole('button')
      .find((b) => b.getAttribute('aria-pressed') === 'true')?.textContent)
      .toBe(nodeConfig.transformSourceInline));
  });

  it('clears the reference when switching back to an inline template', async () => {
    // The executor prefers a reference over a leftover template, so switching must clear it.
    const onUpdate = renderPanel(transformNode({ transformationId: TRANSFORMATION.id }));
    await screen.findByRole('option', { name: /Flatten the customer/ });

    await userEvent.click(within(sourceToggle()).getByRole('button', { name: nodeConfig.transformSourceInline }));

    expect(onUpdate).toHaveBeenCalledWith('node-1', expect.objectContaining({ transformationId: '' }));
  });

  it('selecting a transformation records its id on the node', async () => {
    const onUpdate = renderPanel(transformNode({ transformationId: 'x' }));
    const select = await screen.findByRole('combobox', { name: nodeConfig.transformation });

    await userEvent.selectOptions(select, TRANSFORMATION.id);

    expect(onUpdate).toHaveBeenCalledWith('node-1', expect.objectContaining({
      transformationId: TRANSFORMATION.id,
    }));
  });

  it('creates a transformation without leaving the canvas, and selects it', async () => {
    vi.mocked(transformationsApi.create).mockResolvedValue({ ...TRANSFORMATION, id: 'transformation-new' });
    const onUpdate = renderPanel(transformNode({ transformationId: TRANSFORMATION.id }));

    await userEvent.click(await screen.findByRole('button', { name: nodeConfig.createTransformation }));
    await userEvent.type(screen.getByLabelText(new RegExp(`^${nodeConfig.transformationName}\\s*\\*?$`)), 'Strip PII');
    await userEvent.click(screen.getByRole('button', { name: en.common.create }));

    await waitFor(() => expect(transformationsApi.create).toHaveBeenCalledWith(
      TEST_PROJECT_ID,
      expect.objectContaining({ name: 'Strip PII' }),
    ));
    await waitFor(() => expect(onUpdate).toHaveBeenCalledWith('node-1', expect.objectContaining({
      transformationId: 'transformation-new',
    })));
  });
});
