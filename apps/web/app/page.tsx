import Link from "next/link";
const services = [
  ["Platform API", "Spring Boot · :8080"],
  ["AI Service", "FastAPI · :8000"],
  ["Enterprise Sandbox", "Mock API · :8090"],
];

export default function Home() {
  return (
    <main>
      <nav><span className="mark">OF</span><strong>OntoFlow AI</strong><Link href="/login" className="phase">로그인</Link></nav>
      <section className="hero">
        <p className="eyebrow">ONTOLOGY-DRIVEN ENTERPRISE AX</p>
        <h1>기업의 시스템을<br /><em>하나의 의미</em>로 연결합니다.</h1>
        <p className="intro">분산된 데이터와 업무를 이해하고, 안전한 자동화로 이어지는 엔터프라이즈 AI 플랫폼입니다.</p>
        <Link className="status" href="/register">Workspace 시작하기 →</Link>
      </section>
      <section className="services" aria-label="서비스 상태">
        {services.map(([name, detail]) => <article key={name}><span>ONLINE</span><h2>{name}</h2><p>{detail}</p></article>)}
      </section>
      <footer>OntoFlow AI · Local development foundation</footer>
    </main>
  );
}

