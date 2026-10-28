"use client";
import { useEffect, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { api, ensureSession, logout, ApiError } from "../lib/api";
type Workspace = { id: string; name: string; slug: string; role: string };
export default function Workspaces() {
  const router = useRouter();
  const [items, setItems] = useState<Workspace[]>([]);
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function load(index: number) {
    const result = await api<{ items: Workspace[]; total: number }>(
      `/tenants?page=${index}&size=20`,
    );
    setItems(result.items);
    setTotal(result.total);
  }
  useEffect(() => {
    let active = true;
    ensureSession()
      .then(async (authenticated) => {
        if (!authenticated) {
          if (active) router.replace("/login");
          return null;
        }
        return api<{ items: Workspace[]; total: number }>(
          `/tenants?page=${page}&size=20`,
        );
      })
      .then((result) => {
        if (!result) return;
        if (active) {
          setItems(result.items);
          setTotal(result.total);
        }
      })
      .catch((e) => {
        if (active) {
          if (e instanceof ApiError && e.status === 401)
            router.replace("/login");
          else setError(e.message);
        }
      });
    return () => {
      active = false;
    };
  }, [router, page]);
  async function create(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const form = event.currentTarget;
    const data = new FormData(form);
    try {
      await api("/tenants", {
        method: "POST",
        body: JSON.stringify({
          name: data.get("name"),
          slug: data.get("slug"),
        }),
      });
      form.reset();
      setPage(0);
      await load(0);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }
  async function select(workspace: Workspace) {
    setBusy(true);
    setError("");
    try {
      await api(`/tenants/${workspace.id}/switch`, { method: "POST" });
      router.push(`/dashboard/${workspace.id}`);
    } catch (e) {
      setError((e as Error).message);
      setBusy(false);
    }
  }
  return (
    <main>
      <nav>
        <strong>OntoFlow AI</strong>
        <button
          className="phase"
          onClick={async () => {
            try {
              await logout();
              router.replace("/login");
            } catch (e) {
              setError((e as Error).message);
            }
          }}
        >
          로그아웃
        </button>
      </nav>
      <section className="workspace-section">
        <p className="eyebrow">YOUR WORKSPACES</p>
        <h1>Workspace 선택</h1>
        <p>접근 권한이 있는 기업의 업무 공간을 선택하세요.</p>
        {error && <p role="alert">{error}</p>}
        <div className="services">
          {items.map((w) => (
            <article key={w.id}>
              <span>{w.role}</span>
              <h2>{w.name}</h2>
              <p>{w.slug}</p>
              <button disabled={busy} onClick={() => select(w)}>
                Workspace 열기 →
              </button>
            </article>
          ))}
        </div>
        {!items.length && (
          <p>아직 Workspace가 없습니다. 새 공간을 만들어 시작하세요.</p>
        )}
        <div className="actions">
          <button disabled={page === 0} onClick={() => setPage(page - 1)}>
            이전
          </button>
          <span>{page + 1}</span>
          <button
            disabled={(page + 1) * 20 >= total}
            onClick={() => setPage(page + 1)}
          >
            다음
          </button>
        </div>
        <form className="auth-card" onSubmit={create}>
          <h2>새 Workspace</h2>
          <label>
            기업 이름
            <input name="name" required maxLength={100} />
          </label>
          <label>
            고유 주소
            <input
              name="slug"
              required
              maxLength={80}
              pattern="[a-z0-9]+(-[a-z0-9]+)*"
              placeholder="my-company"
            />
          </label>
          <button disabled={busy}>Workspace 만들기</button>
        </form>
      </section>
    </main>
  );
}
