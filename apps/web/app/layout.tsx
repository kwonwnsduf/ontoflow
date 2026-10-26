import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "OntoFlow AI",
  description: "Ontology-driven enterprise automation",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ko">
      <body>{children}</body>
    </html>
  );
}

