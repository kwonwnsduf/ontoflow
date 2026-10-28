"use client";
import { useParams } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { api } from "../../lib/api";
type Workspace = { name: string; role: string };
type Member = { id: string; email: string; displayName: string; role: string };
export default function Dashboard() {
  const { tenant } = useParams<{ tenant: string }>();
  const [workspace, setWorkspace] = useState<Workspace | null>(null);
  const [members, setMembers] = useState<Member[]>([]);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function load() {
    const w = await api<Workspace>(`/tenants/${tenant}`); setWorkspace(w);
    if (["OWNER", "ADMIN"].includes(w.role)) {
      const m = await api<{ items: Member[]; total: number }>(`/tenants/${tenant}/members?page=${page}&size=20`); setMembers(m.items); setTotal(m.total);
    }
  }
  useEffect(() => {
    let active = true;
    api<Workspace>(`/tenants/${tenant}`).then(async w => {
      if (!active) return;
      setWorkspace(w);
      if (["OWNER", "ADMIN"].includes(w.role)) {
        const result = await api<{ items: Member[]; total: number }>(`/tenants/${tenant}/members?page=${page}&size=20`);
        if (active) { setMembers(result.items); setTotal(result.total); }
      }
    }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [tenant, page]);
  async function mutate(path: string, method: string, body?: object) {
    setBusy(true); setError("");
    try { await api(`/tenants/${tenant}/members${path}`, { method, ...(body ? { body: JSON.stringify(body) } : {}) }); await load(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  function add(event: FormEvent<HTMLFormElement>) { event.preventDefault(); const data = new FormData(event.currentTarget); void mutate("", "POST", { email: data.get("email"), role: data.get("role") }); }
  const roles = workspace?.role === "OWNER" ? ["VIEWER", "OPERATOR", "ADMIN", "OWNER"] : ["VIEWER", "OPERATOR"];
  return <section className="workspace-section"><p className="eyebrow">WORKSPACE OVERVIEW</p><h1>{workspace?.name ?? "불러오는 중…"}</h1><p>내 역할: {workspace?.role}</p>
    {error && <p role="alert">{error}</p>}
    {workspace && ["OWNER", "ADMIN"].includes(workspace.role) && <><h2>멤버 관리</h2><div className="member-list">{members.map(m => <article key={m.id}><h3>{m.displayName}</h3><p>{m.email}</p><div className="actions"><select aria-label={`${m.email} 역할`} value={m.role} disabled={busy || (workspace.role !== "OWNER" && ["ADMIN", "OWNER"].includes(m.role))} onChange={e => void mutate(`/${m.id}`, "PATCH", { role: e.target.value })}>{Array.from(new Set([...roles, m.role])).map(role => <option key={role}>{role}</option>)}</select><button disabled={busy || (workspace.role !== "OWNER" && ["ADMIN", "OWNER"].includes(m.role))} onClick={() => void mutate(`/${m.id}`, "DELETE")}>제거</button></div></article>)}</div>
      <div className="actions"><button disabled={page === 0} onClick={() => setPage(page - 1)}>이전</button><span>{page + 1}</span><button disabled={(page + 1) * 20 >= total} onClick={() => setPage(page + 1)}>다음</button></div>
      <form className="auth-card" onSubmit={add}><h2>등록된 사용자 추가</h2><label>이메일<input type="email" name="email" required /></label><label>역할<select name="role">{roles.map(role => <option key={role}>{role}</option>)}</select></label><button disabled={busy}>멤버 추가</button></form></>}
  </section>;
}
