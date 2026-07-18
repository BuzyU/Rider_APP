'use client';

import { motion, Variants } from 'framer-motion';
import { Coffee, Navigation } from 'lucide-react';
import styles from './MapPreview.module.css';

const textVariants: Variants = {
  hidden: { opacity: 0, y: 30 },
  visible: { 
    opacity: 1, 
    y: 0,
    transition: { duration: 0.8, ease: [0.16, 1, 0.3, 1] }
  }
};

const imageVariants: Variants = {
  hidden: { opacity: 0, scale: 0.95 },
  visible: { 
    opacity: 1, 
    scale: 1,
    transition: { duration: 1, ease: [0.16, 1, 0.3, 1] }
  }
};

export default function MapPreview() {
  return (
    <section className={styles.mapSection}>
      <div className="container">
        <div className={styles.featureRow}>
          <motion.div 
            className={styles.textContent}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: "-100px" }}
            variants={textVariants}
          >
            <h2 className="section-title">Live Group Map.</h2>
            <p className="section-desc">
              Always know where your group is. Drop pins for hazards or the next coffee stop. Never lose a rider at a red light again.
            </p>
          </motion.div>
          
          <motion.div 
            className={styles.visualContent}
            initial="hidden"
            whileInView="visible"
            viewport={{ once: true, margin: "-100px" }}
            variants={imageVariants}
          >
            <div className={styles.mapMockup}>
              {/* Abstract map background pattern */}
              <div className={styles.mapBackground}></div>
              
              {/* Route line */}
              <svg className={styles.routeLine} viewBox="0 0 1000 1000" preserveAspectRatio="none">
                <motion.path 
                  d="M 200 800 Q 500 500 800 200" 
                  fill="transparent" 
                  stroke="var(--safety-orange)" 
                  strokeWidth="3"
                  strokeDasharray="1000"
                  initial={{ strokeDashoffset: 1000 }}
                  whileInView={{ strokeDashoffset: 0 }}
                  transition={{ duration: 1.5, ease: "easeInOut", delay: 0.5 }}
                  viewport={{ once: true }}
                />
              </svg>
              
              {/* Pins */}
              <motion.div 
                className={`${styles.pin} ${styles.riderPin}`} 
                style={{ left: '20%', top: '80%' }}
                initial={{ scale: 0, opacity: 0 }}
                whileInView={{ scale: 1, opacity: 1 }}
                transition={{ type: "spring", stiffness: 200, damping: 15, delay: 0.8 }}
                viewport={{ once: true }}
              >
                <div className={styles.pinIconWrapper}>
                  <Navigation size={20} />
                </div>
                <span>You</span>
              </motion.div>
              
              <motion.div 
                className={`${styles.pin} ${styles.riderPin}`} 
                style={{ left: '50%', top: '50%' }}
                initial={{ scale: 0, opacity: 0 }}
                whileInView={{ scale: 1, opacity: 1 }}
                transition={{ type: "spring", stiffness: 200, damping: 15, delay: 1.2 }}
                viewport={{ once: true }}
              >
                <div className={styles.pinIconWrapper}>
                  <Navigation size={20} />
                </div>
                <span>Alex</span>
              </motion.div>

              <motion.div 
                className={`${styles.pin} ${styles.cafePin}`} 
                style={{ left: '80%', top: '20%' }}
                initial={{ scale: 0, opacity: 0 }}
                whileInView={{ scale: 1, opacity: 1 }}
                transition={{ type: "spring", stiffness: 200, damping: 15, delay: 1.6 }}
                viewport={{ once: true }}
              >
                <div className={styles.pinIconWrapper}>
                  <Coffee size={20} />
                </div>
                <span>Ace Cafe</span>
              </motion.div>
            </div>
          </motion.div>
        </div>
      </div>
    </section>
  );
}
