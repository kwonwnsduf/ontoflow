"use client";
import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";
import Link from "next/link";
import { api, ensureSession, logout, ApiError } from "../../lib/api";
export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { tenant } = useParams<{ tenant: string }>();
  const router = useRouter();
  const [verified, setVerified] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    ensureSession()
      .then(async (authenticated) => {
        if (!authenticated) {
          if (active) router.replace("/login");
          return;
        }
        await api(`/tenants/${tenant}`);
        if (active) setVerified(tenant);
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
  }, [tenant, router]);
  return (
    <main>
      <nav>
        <strong>OntoFlow AI</strong>
        <Link href="/workspaces">Workspace 전환</Link>
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
      {error ? (
        <p role="alert">{error}</p>
      ) : verified === tenant ? (
        children
      ) : (
        <p role="status">Workspace 권한 확인 중…</p>
      )}
    </main>
  );
}
