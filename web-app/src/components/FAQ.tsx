'use client';

import { useState } from 'react';
import { ChevronDown } from 'lucide-react';
import styles from './FAQ.module.css';

const faqs = [
  {
    question: 'Does it need internet to work?',
    answer: 'It uses mobile data for long-range connectivity, but uses ultra-low bandwidth optimized for patchy areas.'
  },
  {
    question: 'Do other riders need the app installed?',
    answer: 'Yes, to talk in the same group, everyone needs the app. But anyone can join your group via a simple link.'
  },
  {
    question: 'Will it drain my battery?',
    answer: 'We built a proprietary VOX engine that sleeps the mic and data connection when no one is talking, saving significant battery.'
  },
  {
    question: 'Does it work with standard wired earphones?',
    answer: 'Absolutely. You don\'t need an expensive dedicated intercom. Just plug in your wired earbuds and start riding.'
  },
  {
    question: 'When are you launching and what will it cost?',
    answer: 'We are launching beta this summer. Early access users get the core features free forever.'
  }
];

export default function FAQ() {
  const [openIndex, setOpenIndex] = useState<number | null>(0);

  const toggle = (index: number) => {
    setOpenIndex(openIndex === index ? null : index);
  };

  return (
    <section className="section">
      <div className="container">
        <div className={styles.header}>
          <h2>Frequently Asked Questions</h2>
        </div>
        
        <div className={styles.faqList}>
          {faqs.map((faq, index) => (
            <div 
              key={index} 
              className={`${styles.faqItem} ${openIndex === index ? styles.open : ''}`}
            >
              <button 
                className={styles.questionBtn} 
                onClick={() => toggle(index)}
              >
                {faq.question}
                <ChevronDown className={styles.icon} size={20} />
              </button>
              <div className={styles.answerWrapper}>
                <p className={styles.answer}>{faq.answer}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
