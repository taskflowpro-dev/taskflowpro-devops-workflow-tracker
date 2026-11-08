import type { Metadata } from "next";
import { AuthProvider } from "./providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "TaskFlow Pro — GitHub project intelligence",
  description: "Understand GitHub repositories, manage projects, and organize engineering follow-up.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" suppressHydrationWarning>
      <body><AuthProvider>{children}</AuthProvider></body>
    </html>
  );
}
