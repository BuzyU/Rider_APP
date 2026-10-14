'use client';

import styles from './Roadmap.module.css';
import TesterApplication from './TesterApplication';

export default function Roadmap() {
  return (
    <section className={styles.roadmapSection} id="apply">
      <div id="beta-testing" style={{ position: 'relative', top: '-100px' }} />
      <div className="container">
        <div className={styles.header}>
          <h2 className="section-title">Join the Closed Beta</h2>
          <p className="section-desc">
            We are rolling out early access to rider convoys in priority waves. Submit your details below to reserve your squad&apos;s spot.
          </p>
        </div>

        <TesterApplication />
      </div>
    </section>
  );
}

