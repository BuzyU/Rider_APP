'use client';

import { motion } from 'framer-motion';
import styles from './TrustLogos.module.css';

export default function TrustLogos() {
  const brands = ["Cardo", "Sena", "AirPods", "TFT Displays", "Any Bluetooth"];

  return (
    <section className={styles.trustSection}>
      <div className="container">
        <motion.div 
          className={styles.wrapper}
          initial={{ opacity: 0, y: 10 }}
          whileInView={{ opacity: 1, y: 0 }}
          viewport={{ once: true, margin: "-50px" }}
          transition={{ duration: 0.8, ease: [0.16, 1, 0.3, 1] }}
        >
          <p className={styles.label}>Works seamlessly with your existing gear</p>
          <div className={styles.logoGrid}>
            {brands.map((brand, idx) => (
              <div key={idx} className={styles.brandBadge}>
                {brand}
              </div>
            ))}
          </div>
        </motion.div>
      </div>
    </section>
  );
}
