'use client';

import { motion } from 'framer-motion';
import { Download, Users, Bike } from 'lucide-react';
import styles from './HowItWorks.module.css';

const steps = [
  {
    icon: Download,
    title: 'Download',
    description: 'Get the app on your phone. No extra hardware or proprietary headsets required.',
  },
  {
    icon: Users,
    title: 'Create/Join a Group',
    description: 'Share a link or code to instantly connect your squad into a secure channel.',
  },
  {
    icon: Bike,
    title: 'Ride & Talk',
    description: 'Put your phone away. The VOX engine handles the rest while you focus on the road.',
  }
];

export default function HowItWorks() {
  return (
    <section className="section" style={{ backgroundColor: 'var(--charcoal)' }}>
      <div className="container">
        <div className={styles.header}>
          <h2>How It Works</h2>
        </div>
        
        <div className={styles.stepsContainer}>
          {steps.map((step, i) => (
            <motion.div 
              key={i}
              className={styles.step}
              initial={{ opacity: 0, x: -30 }}
              whileInView={{ opacity: 1, x: 0 }}
              viewport={{ once: true, margin: "-100px" }}
              transition={{ delay: i * 0.2, duration: 0.5 }}
            >
              <div className={styles.stepNumber}>0{i + 1}</div>
              <div className={styles.iconWrapper}>
                <step.icon size={24} className={styles.icon} />
              </div>
              <div className={styles.content}>
                <h3>{step.title}</h3>
                <p>{step.description}</p>
              </div>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}
