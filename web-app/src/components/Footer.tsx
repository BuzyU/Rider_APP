'use client';

import Link from 'next/link';
import { Mail, MessageSquare, ExternalLink } from 'lucide-react';
import styles from './Footer.module.css';

export default function Footer() {
  return (
    <footer className={styles.footer}>
      <div className="container">
        <div className={styles.grid}>
          <div className={styles.brandCol}>
            <h3 className={styles.logo}>RIDERVOICE</h3>
            <p className={styles.tagline}>Tactical Comms. No Compromise.</p>
            <div className={styles.socials}>
              <a href="https://github.com/BuzyU/Rider_APP" target="_blank" rel="noopener noreferrer" className={styles.socialLink} title="GitHub Repository">
                <ExternalLink size={20} />
              </a>
              <a href="mailto:a07631356@gmail.com" className={styles.socialLink} title="Contact Team">
                <Mail size={20} />
              </a>
            </div>
          </div>
          
          <div className={styles.linksCol}>
            <h4>Platform</h4>
            <a href="#apply">Priority Waitlist</a>
            <a href="#apply">Closed Beta Program</a>
            <a href="https://github.com/BuzyU/Rider_APP/releases" target="_blank" rel="noopener noreferrer">
              GitHub Releases
            </a>
          </div>
          
          <div className={styles.linksCol}>
            <h4>Legal</h4>
            <Link href="/privacy">Privacy Policy</Link>
            <Link href="/terms">Terms of Service</Link>
          </div>
        </div>
        
        <div className={styles.bottomBar}>
          <p>&copy; {new Date().getFullYear()} RiderVoice. Built for riders, by riders. All rights reserved.</p>
        </div>
      </div>
    </footer>
  );
}
