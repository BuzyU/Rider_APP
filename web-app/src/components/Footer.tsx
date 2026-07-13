'use client';

import Link from 'next/link';
import { Mail, MessageSquare, Share2 } from 'lucide-react';
import styles from './Footer.module.css';

export default function Footer() {
  return (
    <footer className={styles.footer}>
      <div className="container">
        <div className={styles.grid}>
          <div className={styles.brandCol}>
            <h3 className={styles.logo}>RIDER APP</h3>
            <p className={styles.tagline}>Ride together. Talk without limits.</p>
            <div className={styles.socials}>
              <a href="#" className={styles.socialLink}><MessageSquare size={20} /></a>
              <a href="#" className={styles.socialLink}><Share2 size={20} /></a>
              <a href="#" className={styles.socialLink}><Mail size={20} /></a>
            </div>
          </div>
          
          <div className={styles.linksCol}>
            <h4>Company</h4>
            <Link href="/about">About Us</Link>
            <Link href="/blog">Blog</Link>
            <Link href="/contact">Contact</Link>
          </div>
          
          <div className={styles.linksCol}>
            <h4>Legal</h4>
            <Link href="/privacy">Privacy Policy</Link>
            <Link href="/terms">Terms of Service</Link>
          </div>
        </div>
        
        <div className={styles.bottomBar}>
          <p>&copy; {new Date().getFullYear()} Rider App Project. All rights reserved.</p>
        </div>
      </div>
    </footer>
  );
}
