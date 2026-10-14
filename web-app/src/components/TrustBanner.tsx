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
          <p className={styles.author}>Built by riders, for riders. Engineered for seamless convoy communication.</p>
          
          <div className={styles.statsRow}>
            <div className={styles.statItem}>
              <span className={styles.statNumber}>Zero</span>
              <span className={styles.statLabel}>Hardware Lock</span>
            </div>
            <div className={styles.statItem}>
              <span className={styles.statNumber}>&lt; 50ms</span>
              <span className={styles.statLabel}>Voice Latency</span>
            </div>
            <div className={styles.statItem}>
              <span className={styles.statNumber}>Cross-Brand</span>
              <span className={styles.statLabel}>Intercom Bridge</span>
            </div>
          </div>
        </motion.div>
      </div>
    </section>
  );
}
