'use client';

import styles from './Roadmap.module.css';

export default function Roadmap() {
  return (
    <section className={styles.roadmapSection}>
      <div className="container">
        <div className={styles.header}>
          <h2 className="section-title">Coming soon to the ride.</h2>
          <p className="section-desc">We&apos;re just getting started. Here&apos;s a look at what we&apos;re building next to make group riding even better.</p>
        </div>
      </div>
    </section>
  );
}
