import type { Metadata } from "next";
import { Inter, Barlow_Condensed } from "next/font/google";
import "./globals.css";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-inter",
  display: 'swap',
});

const barlowCondensed = Barlow_Condensed({
  weight: ['400', '600', '700', '900'],
  subsets: ["latin"],
  variable: "--font-barlow-condensed",
  display: 'swap',
});

export const metadata: Metadata = {
  title: "RiderVoice — Tactical Motorcycle Intercom & Group Comms",
  description: "Hands-free tactical voice intercom for motorcycle squads. Low latency, no proprietary hardware locks, and universal Bluetooth helmet connectivity.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      className={`${inter.variable} ${barlowCondensed.variable}`}
    >
      <body>{children}</body>
    </html>
  );
}
