'use client';

import { motion } from 'framer-motion';
import { Smartphone, Link, BatteryCharging, Network } from 'lucide-react';
import styles from './Features.module.css';

const textVariants = {
  hidden: { opacity: 0, y: 30 },
  visible: { 
    opacity: 1, 
    y: 0,
    transition: { duration: 0.8, ease: [0.16, 1, 0.3, 1] }
  }
};

const imageVariants = {
  hidden: { opacity: 0, scale: 0.95 },
  visible: { 
    opacity: 1, 
    scale: 1,
    transition: { duration: 1, ease: [0.16, 1, 0.3, 1] }
  }
};

export default function Features() {
  return (
    <>
      {/* Feature 1 */}
      <section className={styles.featureSection}>
        <div className="container">
          <div className={styles.featureRow}>
            <motion.div 
              className={styles.textContent}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-100px" }}
              variants={textVariants}
            >
              <h2 className="section-title">Infinite Range VoIP.</h2>
              <p className="section-desc">
                Leave Bluetooth mesh limits behind. Our app uses cellular data to keep your convoy connected in crystal clear audio, whether they are 50 feet or 50 miles away.
              </p>
            </motion.div>
            
            <motion.div 
              className={styles.visualContent}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-100px" }}
              variants={imageVariants}
            >
              <div className={styles.mockupContainer}>
                <div className={styles.mockupPulse} />
                <Network size={64} className={styles.mockupIcon} style={{ color: 'var(--safety-orange)', opacity: 1 }} />
                <div className={styles.connectionLine} />
              </div>
            </motion.div>
          </div>
        </div>
      </section>

      {/* Feature 2 */}
      <section className={styles.featureSection}>
        <div className="container">
          <div className={`${styles.featureRow} ${styles.rowReverse}`}>
            <motion.div 
              className={styles.textContent}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-100px" }}
              variants={textVariants}
            >
              <h2 className="section-title">The Universal Bridge.</h2>
              <p className="section-desc">
                Your friend's Cardo, your Sena, or standard AirPods. Connect them to the app via Bluetooth, and we bridge the gap. Everyone talks in one digital room.
              </p>
            </motion.div>
            
            <motion.div 
              className={styles.visualContent}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-100px" }}
              variants={imageVariants}
            >
              <div className={styles.mockupContainer}>
                <div className={styles.headphoneGroup}>
                  <div className={styles.headphoneItem}>
                    <Smartphone size={40} style={{ opacity: 0.5 }} />
                  </div>
                  <div className={`${styles.headphoneItem} ${styles.active}`}>
                    <Smartphone size={56} />
                  </div>
                  <div className={styles.headphoneItem}>
                    <Smartphone size={40} style={{ opacity: 0.5 }} />
                  </div>
                </div>
              </div>
            </motion.div>
          </div>
        </div>
      </section>

      {/* Feature 3: One-Tap Invites */}
      <section className={styles.featureSection}>
        <div className="container">
          <div className={styles.featureRow}>
            <motion.div 
              className={styles.textContent}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-100px" }}
              variants={textVariants}
            >
              <h2 className="section-title">One-Tap Invites.</h2>
              <p className="section-desc">
                No pairing sequences. No manual channel matching. Just drop a link in your group chat. iOS or Android, your friends tap and they're instantly in the ride.
              </p>
            </motion.div>
            
            <motion.div 
              className={styles.visualContent}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-100px" }}
              variants={imageVariants}
            >
              <div className={styles.mockupContainer}>
                <Link size={64} className={styles.mockupIcon} style={{ color: 'var(--safety-orange)', opacity: 1, marginBottom: 0 }} />
                <div style={{ position: 'absolute', width: '200px', height: '2px', background: 'rgba(0,0,0,0.1)', top: '65%' }}></div>
                <div style={{ position: 'absolute', width: '100px', height: '2px', background: 'var(--safety-orange)', top: '65%', left: '50%', transform: 'translateX(-50%)' }}></div>
              </div>
            </motion.div>
          </div>
        </div>
      </section>

      {/* Feature 4: Background Mode */}
      <section className={styles.featureSection}>
        <div className="container">
          <div className={`${styles.featureRow} ${styles.rowReverse}`}>
            <motion.div 
              className={styles.textContent}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-100px" }}
              variants={textVariants}
            >
              <h2 className="section-title">Lock and Ride.</h2>
              <p className="section-desc">
                The app runs silently in your pocket. Optimized for extreme background efficiency, it preserves your battery for the map and keeps you connected all day.
              </p>
            </motion.div>
            
            <motion.div 
              className={styles.visualContent}
              initial="hidden"
              whileInView="visible"
              viewport={{ once: true, margin: "-100px" }}
              variants={imageVariants}
            >
              <div className={styles.mockupContainer}>
                <BatteryCharging size={64} className={styles.mockupIcon} style={{ color: 'var(--safety-orange)', opacity: 1, marginBottom: 0 }} />
                <div style={{ position: 'absolute', fontSize: '1.5rem', fontWeight: 600, marginTop: '5rem', color: 'var(--text-primary)' }}>
                  12h+ Background
                </div>
              </div>
            </motion.div>
          </div>
        </div>
      </section>
    </>
  );
}
