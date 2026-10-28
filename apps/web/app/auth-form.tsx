"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { api, setSession, type Session } from "./lib/api";

export default function AuthForm({ register = false }: { register?: boolean }) {
  const router = useRouter();
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setBusy(true); setError("");
    const data = new FormData(event.currentTarget);
    try {
      const body = { email: data.get("email"), password: data.get("password"), ...(register ? { displayName: data.get("displayName") } : {}) };
      setSession(await api<Session>(`/auth/${register ? "register" : "login"}`, { method: "POST", body: JSON.stringify(body) }));
      router.replace("/workspaces");
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <main><nav><Link href="/">OntoFlow AI</Link><span className="phase">WORKSPACE</span></nav>
    <section className="auth-card"><p className="eyebrow">ENTERPRISE AX PLATFORM</p><h1>{register ? "계정 만들기" : "다시 만나 반갑습니다"}</h1>
      <p>기업의 데이터와 업무를 하나의 Workspace에서 연결하세요.</p>
      <form onSubmit={submit}>
        {register && <label>이름<input name="displayName" required maxLength={100} autoComplete="name" /></label>}
        <label>이메일<input name="email" type="email" required maxLength={254} autoComplete="email" /></label>
        <label>비밀번호<input name="password" type="password" required minLength={register ? 12 : 1} maxLength={72} pattern={register ? "[\\x20-\\x7E]+" : undefined} autoComplete={register ? "new-password" : "current-password"} /></label>
        {register && <small>영문·숫자·기호 12~72자로 입력하세요.</small>}
        {error && <p role="alert">{error}</p>}<button disabled={busy}>{busy ? "처리 중…" : register ? "회원가입" : "로그인"}</button>
      </form><p><Link href={register ? "/login" : "/register"}>{register ? "이미 계정이 있나요? 로그인" : "새 계정 만들기"}</Link></p>
    </section></main>;
}
