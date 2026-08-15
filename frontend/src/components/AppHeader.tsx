"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const TABS = [
  { href: "/rules", label: "Rule Engine" },
  { href: "/analysis", label: "Analysis" },
];

export function AppHeader() {
  const pathname = usePathname();

  return (
    <header className="srse-header">
      <div className="srse-header-inner">
        <Link href="/rules" className="srse-brand">
          <span className="srse-brand-mark">SRSE</span>
          <span className="srse-brand-sub">Scheme Rule Simulation Engine</span>
        </Link>
        <nav className="srse-nav">
          {TABS.map((tab) => {
            const active = pathname === tab.href || pathname?.startsWith(`${tab.href}/`);
            return (
              <Link
                key={tab.href}
                href={tab.href}
                className={active ? "srse-nav-link active" : "srse-nav-link"}
              >
                {tab.label}
              </Link>
            );
          })}
        </nav>
      </div>
    </header>
  );
}
