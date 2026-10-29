"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useState, type FormEvent } from "react";
import { api } from "../../../lib/api";

type Capability = { action: string; enabled: boolean };
type Connector = {
  id: string; systemType: string; name: string; status: string;
  lastError: string | null; lastCheckedAt: string | null; capabilities: Capability[];
};
type Workspace = { role: string };
type Payment = { id: string; customerId: string; amount: number; currency: string; status: string };

export default function ConnectorsPage() {
  const { tenant } = useParams<{ tenant: string }>();
  const [role, setRole] = useState("");
  const [items, setItems] = useState<Connector[]>([]);
  const [payments, setPayments] = useState<Record<string, Payment[]>>({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState("");
  const admin = role === "OWNER" || role === "ADMIN";

  const load = useCallback(async () => {
    const [workspace, connections] = await Promise.all([
      api<Workspace>(`/tenants/${tenant}`),
      api<Connector[]>(`/tenants/${tenant}/connectors`),
    ]);
    setRole(workspace.role);
    setItems(connections);
  }, [tenant]);

  useEffect(() => {
    let active = true;
    Promise.all([
      api<Workspace>(`/tenants/${tenant}`),
      api<Connector[]>(`/tenants/${tenant}/connectors`),
    ]).then(([workspace, connections]) => {
      if (active) { setRole(workspace.role); setItems(connections); }
    }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [tenant]);

  async function action(work: () => Promise<unknown>, message: string) {
    setBusy(true); setError(""); setNotice("");
    try { await work(); await load(); setNotice(message); }
    catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  function add(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    void action(async () => {
      await api(`/tenants/${tenant}/connectors`, {
        method: "POST",
        body: JSON.stringify({
          systemType: data.get("systemType"),
          name: data.get("name"),
          credential: data.get("credential"),
        }),
      });
      form.reset();
    }, "연결을 등록했습니다.");
  }

  async function showPayments(item: Connector) {
    setError("");
    try {
      const result = await api<Payment[]>(`/tenants/${tenant}/connectors/${item.id}/payments/failed`);
      setPayments(current => ({ ...current, [item.id]: result }));
    } catch (e) { setError((e as Error).message); }
  }

  return <section className="workspace-section">
    <p className="eyebrow">ENTERPRISE CONNECTIONS</p>
    <h1>연결 시스템</h1>
    <p>워크스페이스별 기업 시스템과 사용 가능한 기능을 관리합니다.</p>
    {error && <p role="alert">{error}</p>}
    {notice && <p role="status">{notice}</p>}
    {admin && <form className="auth-card" onSubmit={add}>
      <h2>시스템 추가</h2>
      <label>기업 시스템<select name="systemType"><option value="ALPHA">Alpha Payment · Salesforce형 CRM · Mail</option><option value="BETA">Beta Payment · Custom CRM · Mail</option></select></label>
      <label>연결 이름<input name="name" maxLength={100} required /></label>
      <label>API Credential<input name="credential" type="password" autoComplete="off" required /></label>
      <button disabled={busy}>연결 등록 및 테스트</button>
    </form>}
    <div className="member-list">
      {items.map(item => <article key={item.id}>
        <span>{item.systemType}</span><h2>{item.name}</h2>
        <p>상태: {item.status}{item.lastCheckedAt ? ` · 마지막 확인 ${new Date(item.lastCheckedAt).toLocaleString("ko-KR")}` : ""}</p>
        {item.lastError && <p role="alert">{item.lastError}</p>}
        <div className="actions">
          <button onClick={() => void showPayments(item)}>실패 결제 조회</button>
          {admin && <button disabled={busy} onClick={() => void action(() => api(`/tenants/${tenant}/connectors/${item.id}/test`, { method: "POST" }), "연결을 확인했습니다.")}>연결 테스트</button>}
          {admin && <button disabled={busy} onClick={() => { if (window.confirm("연결을 해제할까요?")) void action(() => api(`/tenants/${tenant}/connectors/${item.id}`, { method: "DELETE" }), "연결을 해제했습니다."); }}>연결 해제</button>}
        </div>
        <h3>Capability</h3>
        <ul>{item.capabilities.map(capability => <li key={capability.action}>
          {admin ? <label className="capability"><input type="checkbox" checked={capability.enabled} disabled={busy} onChange={e => void action(() => api(`/tenants/${tenant}/connectors/${item.id}/capabilities/${capability.action}`, { method: "PATCH", body: JSON.stringify({ enabled: e.target.checked }) }), "Capability를 변경했습니다.")} />{capability.action}</label> : <span>{capability.action} · {capability.enabled ? "활성" : "비활성"}</span>}
        </li>)}</ul>
        {payments[item.id] && <><h3>실패 결제</h3><ul>{payments[item.id].map(payment => <li key={payment.id}>{payment.id} · {payment.customerId} · {payment.amount.toLocaleString("ko-KR")} {payment.currency}</li>)}</ul></>}
      </article>)}
      {!items.length && <p>등록된 시스템이 없습니다.</p>}
    </div>
  </section>;
}
