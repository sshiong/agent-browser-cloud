import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  agentConnectionsApi,
  type AgentConnection,
  type AgentTool,
} from '@/api/agentConnections';

const tools: [AgentTool, string][] = [
  ['quota', '读取额度'],
  ['list', '环境列表'],
  ['get', '环境详情'],
  ['create', '创建环境'],
  ['start', '启动环境'],
  ['stop', '停止环境'],
  ['delete', '删除环境'],
  ['operation', '操作进度'],
];
const field =
  'w-full border border-border-subtle bg-surface-2 p-2 text-sm text-text-primary';
const button =
  'border border-border-subtle px-3 py-2 text-sm text-text-primary disabled:opacity-50';

export function AgentConnectionsSection() {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: ['agent-connections'],
    queryFn: agentConnectionsApi.list,
  });
  const [name, setName] = useState('我的 Agent');
  const [total, setTotal] = useState(20);
  const [running, setRunning] = useState(3);
  const [days, setDays] = useState(7);
  const [selected, setSelected] = useState<AgentTool[]>(
    tools.map(([key]) => key)
  );
  const [issued, setIssued] = useState<{
    connection: AgentConnection;
    accessToken: string;
  }>();
  const [url, setUrl] = useState(
    import.meta.env.VITE_MCP_URL || 'http://127.0.0.1:8130/mcp'
  );
  const [copied, setCopied] = useState(false);
  const create = useMutation({
    mutationFn: () =>
      agentConnectionsApi.create({
        name,
        maximumEnvironments: total,
        maximumRunning: running,
        expiresAt: new Date(Date.now() + days * 86400000).toISOString(),
        allowedTools: selected,
      }),
    onSuccess: (result) => {
      setIssued(result);
      setCopied(false);
      void client.invalidateQueries({ queryKey: ['agent-connections'] });
    },
  });
  return (
    <section
      id="agent-connections"
      className="border border-border-subtle bg-surface-1 p-4 sm:p-6"
      aria-labelledby="agent-connections-heading"
    >
      <h2
        id="agent-connections-heading"
        className="mb-2 text-[15px] font-semibold text-text-primary"
      >
        Agent 连接 · 可选 MCP
      </h2>
      <p className="mb-4 text-sm text-text-secondary">
        需要 Agent 接入时启用 MCP Gateway。关闭 Gateway
        不会删除已创建的环境。连接仅能管理自己创建的环境。
      </p>
      <form
        className="grid gap-3 sm:grid-cols-2"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate();
        }}
      >
        <label className="text-sm text-text-secondary">
          连接名称
          <input
            className={field}
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            maxLength={128}
          />
        </label>
        <label className="text-sm text-text-secondary">
          有效天数
          <input
            className={field}
            type="number"
            min={1}
            max={365}
            value={days}
            onChange={(e) => setDays(Number(e.target.value))}
            required
          />
        </label>
        <label className="text-sm text-text-secondary">
          可保留环境总数
          <input
            className={field}
            type="number"
            min={0}
            max={10000}
            value={total}
            onChange={(e) => setTotal(Number(e.target.value))}
            required
          />
        </label>
        <label className="text-sm text-text-secondary">
          同时运行上限
          <input
            className={field}
            type="number"
            min={0}
            max={10000}
            value={running}
            onChange={(e) => setRunning(Number(e.target.value))}
            required
          />
        </label>
        <fieldset className="flex flex-wrap gap-3 sm:col-span-2">
          <legend className="mb-2 text-sm text-text-secondary">
            允许的操作
          </legend>
          {tools.map(([key, label]) => (
            <label
              key={key}
              className="flex items-center gap-1 text-sm text-text-primary"
            >
              <input
                type="checkbox"
                checked={selected.includes(key)}
                onChange={(e) =>
                  setSelected(
                    e.target.checked
                      ? [...selected, key]
                      : selected.filter((x) => x !== key)
                  )
                }
              />
              {label}
            </label>
          ))}
        </fieldset>
        <button
          className={button}
          disabled={create.isPending || selected.length === 0}
        >
          {create.isPending ? '正在创建…' : '创建 Agent 连接'}
        </button>
      </form>
      {create.isError && (
        <p role="alert" className="mt-3 text-sm text-danger">
          创建失败，请检查权限、名称和额度后重试。
        </p>
      )}
      {issued && (
        <div className="mt-4 space-y-3 border border-accent p-3">
          <p className="text-sm text-text-primary">
            凭据仅显示这一次，请安全保存。不要放入 URL 或提交到代码仓库。
          </p>
          <label className="block text-sm text-text-secondary">
            MCP 地址
            <input
              className={field}
              value={url}
              onChange={(e) => {
                setUrl(e.target.value);
                setCopied(false);
              }}
            />
          </label>
          <pre className="overflow-x-auto whitespace-pre-wrap break-all text-xs text-text-primary">
            {issued.accessToken}
          </pre>
          <button
            className={button}
            onClick={() => {
              void navigator.clipboard
                .writeText(
                  JSON.stringify(
                    {
                      mcpServers: {
                        browsercloud: {
                          url,
                          headers: {
                            Authorization: `Bearer ${issued.accessToken}`,
                          },
                        },
                      },
                    },
                    null,
                    2
                  )
                )
                .then(() => setCopied(true))
                .catch(() => setCopied(false));
            }}
          >
            {copied ? '配置已复制' : '复制客户端配置'}
          </button>
          <button
            className={`${button} ml-2`}
            onClick={() => setIssued(undefined)}
          >
            已保存，隐藏凭据
          </button>
        </div>
      )}
      {query.isPending && (
        <p className="mt-4 text-sm text-text-secondary">正在读取连接…</p>
      )}
      {query.isError && (
        <p role="alert" className="mt-4 text-sm text-danger">
          连接列表读取失败。
          <button className={button} onClick={() => void query.refetch()}>
            重试
          </button>
        </p>
      )}
      {query.data?.length === 0 && (
        <p className="mt-4 text-sm text-text-secondary">暂无 Agent 连接。</p>
      )}
      <div className="mt-4 space-y-3">
        {query.data?.map((connection) => (
          <ConnectionRow
            key={connection.connectionId}
            connection={connection}
            onRevoked={() => {
              if (issued?.connection.connectionId === connection.connectionId)
                setIssued(undefined);
            }}
          />
        ))}
      </div>
    </section>
  );
}
function ConnectionRow({
  connection,
  onRevoked,
}: {
  connection: AgentConnection;
  onRevoked: () => void;
}) {
  const client = useQueryClient();
  const [total, setTotal] = useState(connection.maximumEnvironments);
  const [running, setRunning] = useState(connection.maximumRunning);
  const update = useMutation({
    mutationFn: () =>
      agentConnectionsApi.configure(connection.connectionId, total, running),
    onSuccess: () =>
      void client.invalidateQueries({ queryKey: ['agent-connections'] }),
  });
  const revoke = useMutation({
    mutationFn: () => agentConnectionsApi.revoke(connection.connectionId),
    onSuccess: () => {
      onRevoked();
      void client.invalidateQueries({ queryKey: ['agent-connections'] });
    },
  });
  const active =
    connection.enabled && new Date(connection.expiresAt).getTime() > Date.now();
  return (
    <div className="space-y-2 border border-border-subtle p-3 text-sm text-text-primary">
      <div className="flex flex-wrap justify-between gap-2">
        <strong>{connection.name}</strong>
        <span>{active ? '有效' : '已撤销或过期'}</span>
      </div>
      <p className="break-all text-xs text-text-secondary">
        {connection.connectionId} · 到期{' '}
        {new Date(connection.expiresAt).toLocaleString()}
      </p>
      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          update.mutate();
        }}
      >
        <label>
          环境总数
          <input
            aria-label={`${connection.name} 环境总数`}
            className={field}
            type="number"
            min={0}
            max={10000}
            value={total}
            onChange={(e) => setTotal(Number(e.target.value))}
          />
        </label>
        <label>
          运行上限
          <input
            aria-label={`${connection.name} 运行上限`}
            className={field}
            type="number"
            min={0}
            max={10000}
            value={running}
            onChange={(e) => setRunning(Number(e.target.value))}
          />
        </label>
        <button className={button} disabled={!active || update.isPending}>
          保存额度
        </button>
        <button
          className={button}
          type="button"
          disabled={!active || revoke.isPending}
          onClick={() => revoke.mutate()}
        >
          撤销连接
        </button>
      </form>
      {(update.isError || revoke.isError) && (
        <p role="alert" className="text-danger">
          操作失败，请重试。
        </p>
      )}
      {update.isSuccess && (
        <p role="status" className="text-text-secondary">
          额度已保存。
        </p>
      )}
    </div>
  );
}
