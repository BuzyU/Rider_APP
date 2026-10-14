import React from "react";
import Hero from "@/components/Hero";
import TrustLogos from "@/components/TrustLogos";
import TrustBanner from "@/components/TrustBanner";
import Features from "@/components/Features";
import MapPreview from "@/components/MapPreview";
import Roadmap from "@/components/Roadmap";
import Footer from "@/components/Footer";

export default function Home() {
  return (
    <div style={{ maxWidth: '100%', overflowX: 'hidden' }}>
      <main>
        <Hero />
        <TrustLogos />
        <TrustBanner />
        <Features />
        <MapPreview />
        <Roadmap />

        <section className="section" style={{ minHeight: '40vh', display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', textAlign: 'center', padding: '6rem 0' }}>
          <div className="container" style={{ maxWidth: '680px' }}>
            <h2 className="section-title">Never ride disconnected.</h2>
            <p className="section-desc" style={{ marginBottom: '2.5rem', marginLeft: 'auto', marginRight: 'auto' }}>
              Ready to elevate your group rides? Reserve your convoy&apos;s spot in our closed beta priority testing wave.
            </p>
            <div>
              <a
                href="#apply"
                className="btn btn-primary"
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: '0.75rem',
                  padding: '1.15rem 2.5rem',
                  fontSize: '1.125rem',
                  fontWeight: 600,
                  textDecoration: 'none',
                  boxShadow: '0 10px 30px rgba(255, 85, 0, 0.25)'
                }}
              >
                Apply for Priority Access &rarr;
              </a>
            </div>
          </div>
        </section>
      </main>
      <Footer />
    </div>
  );
}
