import type { Metadata } from "next";
import { AuthProvider } from "./providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "TaskFlow Pro — Work, in flow",
  description: "A calmer, clearer way to move work forward.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en">
      <body><AuthProvider>{children}</AuthProvider></body>
    </html>
  );
}
