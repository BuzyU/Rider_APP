'use client';

import { motion } from 'framer-motion';
import styles from './TrustBanner.module.css';

export default function TrustBanner() {
  return (
    <section className={styles.trustSection}>
      <div className="container">
        <motion.div
          initial={{ opacity: 0, y: 20 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true, margin: "-100px" }}
          transition={{ duration: 0.8, ease: [0.16, 1, 0.3, 1] }}
        >
          <h2 className={styles.quote}>
            &ldquo;The first intercom app that actually keeps the convoy together.&rdquo;
          </h2>
          <p className={styles.author}>Built by riders, for riders. Join the thousands already on the waitlist.</p>
          
          <div className={styles.statsRow}>
            <div className={styles.statItem}>
              <span className={styles.statNumber}>10k+</span>
              <span className={styles.statLabel}>Waitlist</span>
            </div>
            <div className={styles.statItem}>
              <span className={styles.statNumber}>0 sec</span>
              <span className={styles.statLabel}>Pairing Time</span>
            </div>
            <div className={styles.statItem}>
              <span className={styles.statNumber}>Unlimited</span>
              <span className={styles.statLabel}>Range</span>
            </div>
          </div>
        </motion.div>
      </div>
    </section>
  );
}
