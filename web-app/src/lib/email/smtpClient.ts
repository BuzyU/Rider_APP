import nodemailer from 'nodemailer';

export interface SendMailOptions {
  to: string;
  subject: string;
  html: string;
  text?: string;
  user?: string;
  pass?: string;
  fromName?: string;
  host?: string;
  port?: number;
}

export interface SendMailResult {
  success: boolean;
  messageId?: string;
  error?: string;
}

export async function sendSmtpMail(options: SendMailOptions): Promise<SendMailResult> {
  const user = options.user || process.env.SMTP_USER || process.env.GMAIL_USER || 'a07631356@gmail.com';
  const rawPass = options.pass || process.env.SMTP_PASS || process.env.GMAIL_APP_PASSWORD || 'orvr bsie eyks kbyb';
  const pass = rawPass.replace(/["'\s]+/g, '');
  const host = options.host || process.env.SMTP_HOST || 'smtp.gmail.com';
  const port = options.port || Number(process.env.SMTP_PORT) || 465;
  const fromName = options.fromName || process.env.SMTP_FROM_NAME || 'RiderVoice Comms';

  const to = options.to.trim();
  const subject = options.subject;
  const html = options.html;
  const text = options.text || html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();

  const transporter = nodemailer.createTransport({
    host,
    port,
    secure: port === 465,
    auth: {
      user,
      pass,
    },
    tls: {
      rejectUnauthorized: true,
    },
  });

  const info = await transporter.sendMail({
    from: `"${fromName}" <${user}>`,
    replyTo: `"${fromName}" <${user}>`,
    to,
    subject,
    text,
    html,
    headers: {
      'X-Entity-Ref-ID': `${Date.now()}-${Math.random().toString(36).substring(2, 8)}`,
    },
  });

  return {
    success: true,
    messageId: info.messageId,
  };
}
