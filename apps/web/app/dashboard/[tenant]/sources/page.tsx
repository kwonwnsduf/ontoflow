"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api } from "../../../lib/api";

type Resource = { key: string; type: string; resourceType: string };
type Source = { id: string; type: string; name: string; resourceKey: string; status: string;
  lastError: string | null; lastCheckedAt: string | null; lastSyncedAt: string | null; readEnabled: boolean;
  syncStatus: string; syncCursor: string | null; allowedScope: string | null };
type RecordItem = { sourceId: string; sourceUrl: string | null; collectedAt: string;
  permissionScope: string; checksum: string; deleted: boolean; payload: string | null };
type Workspace = { role: string };

export default function SourcesPage() {
  const { tenant } = useParams<{ tenant: string }>();
  const [role, setRole] = useState("");
  const [resources, setResources] = useState<Resource[]>([]);
  const [sources, setSources] = useState<Source[]>([]);
  const [selected, setSelected] = useState("");
  const [records, setRecords] = useState<RecordItem[]>([]);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const admin = role === "OWNER" || role === "ADMIN";
  const resource = resources.find(item => item.key === selected);

  const load = useCallback(async () => {
    const [workspace, available, connected] = await Promise.all([
      api<Workspace>(`/tenants/${tenant}`),
      api<Resource[]>(`/tenants/${tenant}/sources/available`),
      api<Source[]>(`/tenants/${tenant}/sources`),
    ]);
    setRole(workspace.role);
    setResources(available);
    setSources(connected);
    setSelected(current => current || available[0]?.key || "");
  }, [tenant]);

  useEffect(() => {
    let active = true;
    Promise.all([
      api<Workspace>(`/tenants/${tenant}`),
      api<Resource[]>(`/tenants/${tenant}/sources/available`),
      api<Source[]>(`/tenants/${tenant}/sources`),
    ]).then(([workspace, available, connected]) => {
      if (active) {
        setRole(workspace.role);
        setResources(available);
        setSources(connected);
        setSelected(available[0]?.key || "");
      }
    }).catch(e => { if (active) setError((e as Error).message); });
    return () => { active = false; };
  }, [tenant]);

  async function action(work: () => Promise<unknown>, message: string) {
    setBusy(true); setError(""); setNotice("");
    try { await work(); await load(); setNotice(message); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  function connect(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    if (!resource) return;
    const name = String(data.get("name") || "");
    if (resource.type === "SLACK") {
      void action(async () => {
        const result = await api<{ authorizeUrl: string }>(`/tenants/${tenant}/sources/slack/authorize`, {
          method: "POST", body: JSON.stringify({ name, resourceKey: resource.key }),
        });
        window.location.assign(result.authorizeUrl);
      }, "Slack 동의 화면으로 이동합니다.");
      return;
    }
    if (resource.type === "GITHUB") {
      void action(async () => {
        const result = await api<{ authorizeUrl: string }>(`/tenants/${tenant}/sources/github/authorize`, {
          method: "POST", body: JSON.stringify({ name, resourceKey: resource.key,
            installationId: String(data.get("installationId") || "") }),
        });
        window.location.assign(result.authorizeUrl);
      }, "GitHub 접근 확인 화면으로 이동합니다.");
      return;
    }
    const credential = JSON.stringify({ username: data.get("username"), password: data.get("password") });
    void action(async () => {
      await api(`/tenants/${tenant}/sources`, {
        method: "POST",
        body: JSON.stringify({ type: resource.type, name, resourceKey: resource.key, credential }),
      });
      form.reset();
    }, "데이터 원천을 등록했습니다.");
  }

  async function showRecords(source: Source) {
    setError("");
    try {
      setRecords(await api<RecordItem[]>(`/tenants/${tenant}/sources/${source.id}/provenance?limit=50`));
    } catch (e) { setError((e as Error).message); }
  }

  async function installGithub() {
    setError("");
    try {
      const result = await api<{ installUrl: string }>(`/tenants/${tenant}/sources/github/install-url`);
      window.location.assign(result.installUrl);
    } catch (e) { setError((e as Error).message); }
  }

  return <section className="workspace-section">
    <p className="eyebrow">ENTERPRISE DATA SOURCES</p>
    <h1>데이터 원천</h1>
    <p>서버에서 허용한 View, Repository, 채널만 연결하고 조회합니다.</p>
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {admin && <form className="auth-card" onSubmit={connect}>
      <h2>원천 연결</h2>
      <label>허용된 리소스<select value={selected} onChange={e => setSelected(e.target.value)} required>
        {resources.map(item => <option key={item.key} value={item.key}>{item.key} · {item.type} · {item.resourceType}</option>)}
      </select></label>
      <label>연결 이름<input name="name" maxLength={100} required /></label>
      {(resource?.type === "POSTGRESQL" || resource?.type === "MYSQL") && <>
        <label>읽기 전용 사용자<input name="username" autoComplete="off" required /></label>
        <label>비밀번호<input name="password" type="password" autoComplete="off" required /></label>
      </>}
      {resource?.type === "GITHUB" && <>
        <button type="button" onClick={() => void installGithub()}>GitHub App 설치</button>
        <label>설치 후 받은 ID<input name="installationId" inputMode="numeric" pattern="[1-9][0-9]*" required /></label>
        <p>설치 ID와 Repository 접근 권한을 GitHub 사용자 동의로 확인합니다.</p>
      </>}
      {resource?.type === "SLACK" && <p>Slack 동의 화면에서 해당 채널에 접근할 Bot 권한을 승인합니다.</p>}
      <button disabled={busy || !resource}>{resource?.type === "SLACK" ? "Slack 동의" : resource?.type === "GITHUB" ? "GitHub 접근 확인" : "연결 등록 및 테스트"}</button>
    </form>}
    <div className="member-list">
      {sources.map(source => <article key={source.id}>
        <span>{source.type} · {source.resourceKey}</span><h2>{source.name}</h2>
        <p>상태: {source.status} · 읽기 {source.readEnabled ? "활성" : "비활성"}</p>
        {source.allowedScope && <p>허용 범위: {source.allowedScope}</p>}
        <p>동기화: {source.syncStatus}{source.syncCursor ? " · 재개 가능" : ""}</p>
        {source.lastCheckedAt && <p>마지막 검사: {new Date(source.lastCheckedAt).toLocaleString("ko-KR")}</p>}
        {source.lastSyncedAt && <p>마지막 동기화: {new Date(source.lastSyncedAt).toLocaleString("ko-KR")}</p>}
        {source.lastError && <p role="alert">{source.lastError}</p>}
        <div className="actions">
          <button onClick={() => void showRecords(source)}>출처 및 삭제 상태</button>
          {admin && <button disabled={busy || source.status === "DISCONNECTED"} onClick={() => void action(() => api(`/tenants/${tenant}/sources/${source.id}/test`, { method: "POST" }), "연결을 확인했습니다.")}>연결 테스트</button>}
          {admin && <button disabled={busy || !source.readEnabled || source.status !== "CONNECTED"} onClick={() => void action(() => api(`/tenants/${tenant}/sources/${source.id}/sync`, { method: "POST" }), "동기화를 실행했습니다.")}>동기화</button>}
          {admin && source.status !== "DISCONNECTED" && <button disabled={busy} onClick={() => void action(() => api(`/tenants/${tenant}/sources/${source.id}/capability`, { method: "PATCH", body: JSON.stringify({ enabled: !source.readEnabled }) }), "읽기 권한을 변경했습니다.")}>{source.readEnabled ? "읽기 중지" : "읽기 재개"}</button>}
          {admin && source.status !== "DISCONNECTED" && <button disabled={busy} onClick={() => { if (window.confirm("연결을 해제하고 수집 데이터를 비공개로 전환할까요?")) void action(() => api(`/tenants/${tenant}/sources/${source.id}`, { method: "DELETE" }), "연결을 해제했습니다."); }}>연결 해제</button>}
        </div>
      </article>)}
      {!sources.length && <p>등록된 데이터 원천이 없습니다.</p>}
    </div>
    {!!records.length && <div className="member-list"><h2>수집된 원본</h2>
      {records.map(record => <article key={record.sourceId}>
        <h3>{record.sourceId} · {record.deleted ? "삭제·비노출" : "활성"}</h3>
        <p>출처: {record.permissionScope} · 수집: {new Date(record.collectedAt).toLocaleString("ko-KR")}</p>
        {record.sourceUrl && <a href={record.sourceUrl} target="_blank" rel="noreferrer">원본 열기</a>}
        {record.payload && <details><summary>원본 데이터</summary><pre>{record.payload}</pre></details>}
      </article>)}
    </div>}
  </section>;
}
