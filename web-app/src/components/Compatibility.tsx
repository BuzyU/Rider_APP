'use client';

import { Bluetooth, Cable, Ear } from 'lucide-react';
import styles from './Compatibility.module.css';

export default function Compatibility() {
  return (
    <section className="section">
      <div className="container">
        <div className={styles.wrapper}>
          <div className={styles.textColumn}>
            <h2>Play nice with everyone.</h2>
            <p>
              Works with major intercom brands and standard wired audio. 
              No new hardware needed. Just plug in and ride.
            </p>
          </div>
          
          <div className={styles.logoStrip}>
            <div className={styles.iconBox}>
              <Bluetooth size={40} className={styles.icon} />
              <span>Sena</span>
            </div>
            <div className={styles.iconBox}>
              <Bluetooth size={40} className={styles.icon} />
              <span>Cardo</span>
            </div>
            <div className={styles.iconBox}>
              <Cable size={40} className={styles.icon} />
              <span>Wired</span>
            </div>
            <div className={styles.iconBox}>
              <Ear size={40} className={styles.icon} />
              <span>AirPods</span>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
