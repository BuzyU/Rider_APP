import React from "react";
import Hero from "@/components/Hero";
import Features from "@/components/Features";
import MapPreview from "@/components/MapPreview";
import Footer from "@/components/Footer";
import WaitlistForm from "@/components/WaitlistForm";

export default function Home() {
  return (
    <div style={{ maxWidth: '100%', overflowX: 'hidden' }}>
      <main>
        <Hero />
        
        <Features />
        
        <MapPreview />

        {/* Final Waitlist Section */}
        <section className="section" style={{ minHeight: '60vh', display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', textAlign: 'center' }}>
          <div className="container" style={{ maxWidth: '600px' }}>
            <h2 className="section-title">Ride connected.</h2>
            <p className="section-desc" style={{ marginBottom: '4rem', marginLeft: 'auto', marginRight: 'auto' }}>
              Join the waitlist. Be the first to get access.
            </p>
            <WaitlistForm id="footer-waitlist" />
          </div>
        </section>
      </main>
      <Footer />
    </div>
  );
}
