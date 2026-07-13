'use client';

import { motion } from 'framer-motion';
import WaitlistForm from './WaitlistForm';
import styles from './Hero.module.css';
import { useEffect, useRef } from 'react';

export default function Hero() {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animationFrameId: number;
    let t = 0;

    const resize = () => {
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
    };
    
    window.addEventListener('resize', resize);
    resize();

    const draw = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      ctx.lineWidth = 1; // Thinner, more elegant lines
      
      const centerY = canvas.height / 2;
      
      // Draw a subtle animated wave (kinetic road/signal vibe)
      ctx.beginPath();
      for (let x = 0; x < canvas.width; x++) {
        const y = centerY + Math.sin(x * 0.005 + t) * 60 * Math.sin(t * 0.2);
        if (x === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      
      ctx.strokeStyle = 'rgba(255, 85, 0, 0.3)'; // Slightly stronger Safety Orange for light mode
      ctx.stroke();

      // Draw secondary wave
      ctx.beginPath();
      for (let x = 0; x < canvas.width; x++) {
        const y = centerY + Math.sin(x * 0.008 - t * 0.8) * 40 * Math.cos(t * 0.15);
        if (x === 0) ctx.moveTo(x, y);
        else ctx.lineTo(x, y);
      }
      
      ctx.strokeStyle = 'rgba(0, 0, 0, 0.08)'; // Very faint black
      ctx.stroke();

      t += 0.01;
      animationFrameId = requestAnimationFrame(draw);
    };

    draw();

    return () => {
      window.removeEventListener('resize', resize);
      cancelAnimationFrame(animationFrameId);
    };
  }, []);

  return (
    <section className={styles.heroSection}>
      <canvas ref={canvasRef} className={styles.canvasBackground} />
      <div className="container" style={{ position: 'relative', zIndex: 1 }}>
        <div className={styles.content}>
          <motion.h1 
            className={`hero-title ${styles.title}`}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 1, ease: [0.16, 1, 0.3, 1] }}
          >
            Tactical comms.<br />
            No compromise.
          </motion.h1>
          
          <motion.p 
            className={styles.subtitle}
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 1, delay: 0.2, ease: [0.16, 1, 0.3, 1] }}
          >
            Ride together. Talk without limits. No phone, no app-locking, no brand walls.
          </motion.p>

          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 1, delay: 0.4, ease: [0.16, 1, 0.3, 1] }}
            className={styles.formWrapper}
          >
            <WaitlistForm id="hero-waitlist" />
          </motion.div>
        </div>
      </div>
    </section>
  );
}
