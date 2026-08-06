import type { ReactNode } from "react";
import "./globals.css";

export const metadata = {
  title: "SRSE",
  description: "Scheme Rule Simulation Engine",
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
