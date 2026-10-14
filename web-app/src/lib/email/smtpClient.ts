import tls from 'node:tls';

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

/**
 * Native Node.js TLS SMTP client for Google SMTP (smtp.gmail.com:465).
 * Zero external dependencies required.
 */
export async function sendSmtpMail(options: SendMailOptions): Promise<SendMailResult> {
  const user = options.user || process.env.SMTP_USER || process.env.GMAIL_USER || 'a07631356@gmail.com';
  const rawPass = options.pass || process.env.SMTP_PASS || process.env.GMAIL_APP_PASSWORD || 'orvr bsie eyks kbyb';
  const cleanPass = rawPass.replace(/\s+/g, '');
  const host = options.host || process.env.SMTP_HOST || 'smtp.gmail.com';
  const port = options.port || Number(process.env.SMTP_PORT) || 465;
  const fromName = options.fromName || process.env.SMTP_FROM_NAME || 'RiderVoice Comms';

  const to = options.to.trim();
  const subject = options.subject;
  const html = options.html;
  const text = options.text || html.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();

  return new Promise((resolve, reject) => {
    let resolved = false;

    const timeout = setTimeout(() => {
      if (!resolved) {
        resolved = true;
        try { socket.destroy(); } catch {}
        reject(new Error(`SMTP connection timed out after 12 seconds`));
      }
    }, 12000);

    const socket = tls.connect(
      {
        host,
        port,
        rejectUnauthorized: true,
      },
      () => {
        // Connected
      }
    );

    let buffer = '';
    let stage:
      | 'GREETING'
      | 'EHLO'
      | 'AUTH_LOGIN'
      | 'AUTH_USER'
      | 'AUTH_PASS'
      | 'MAIL_FROM'
      | 'RCPT_TO'
      | 'DATA'
      | 'SEND_BODY'
      | 'QUIT' = 'GREETING';

    const sendCmd = (cmd: string) => {
      socket.write(cmd + '\r\n');
    };

    socket.setEncoding('utf8');

    socket.on('data', (chunk: string) => {
      buffer += chunk;
      const lines = buffer.split('\r\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.trim()) continue;

        const code = line.slice(0, 3);
        const isLastLineOfReply = line.charAt(3) === ' ' || line.charAt(3) === '';

        if (!isLastLineOfReply) continue;

        if (stage === 'GREETING' && code === '220') {
          stage = 'EHLO';
          sendCmd('EHLO localhost');
        } else if (stage === 'EHLO' && code === '250') {
          stage = 'AUTH_LOGIN';
          sendCmd('AUTH LOGIN');
        } else if (stage === 'AUTH_LOGIN' && code === '334') {
          stage = 'AUTH_USER';
          sendCmd(Buffer.from(user).toString('base64'));
        } else if (stage === 'AUTH_USER' && code === '334') {
          stage = 'AUTH_PASS';
          sendCmd(Buffer.from(cleanPass).toString('base64'));
        } else if (stage === 'AUTH_PASS' && code === '235') {
          stage = 'MAIL_FROM';
          sendCmd(`MAIL FROM:<${user}>`);
        } else if (stage === 'MAIL_FROM' && code === '250') {
          stage = 'RCPT_TO';
          sendCmd(`RCPT TO:<${to}>`);
        } else if (stage === 'RCPT_TO' && code === '250') {
          stage = 'DATA';
          sendCmd('DATA');
        } else if (stage === 'DATA' && code === '354') {
          stage = 'SEND_BODY';
          const boundary = `====_RiderVoice_${Date.now()}_====`;
          const rawMessage = [
            `From: "${fromName}" <${user}>`,
            `To: <${to}>`,
            `Subject: ${subject}`,
            'MIME-Version: 1.0',
            `Content-Type: multipart/alternative; boundary="${boundary}"`,
            '',
            `--${boundary}`,
            'Content-Type: text/plain; charset=UTF-8',
            'Content-Transfer-Encoding: 7bit',
            '',
            text,
            '',
            `--${boundary}`,
            'Content-Type: text/html; charset=UTF-8',
            'Content-Transfer-Encoding: 7bit',
            '',
            html,
            '',
            `--${boundary}--`,
            '.'
          ].join('\r\n');

          sendCmd(rawMessage);
        } else if (stage === 'SEND_BODY' && code === '250') {
          stage = 'QUIT';
          sendCmd('QUIT');
          clearTimeout(timeout);
          if (!resolved) {
            resolved = true;
            resolve({ success: true, messageId: line });
          }
        } else if (code.startsWith('4') || code.startsWith('5')) {
          clearTimeout(timeout);
          if (!resolved) {
            resolved = true;
            socket.end();
            reject(new Error(`SMTP Error [${code}]: ${line}`));
          }
        }
      }
    });

    socket.on('error', (err) => {
      clearTimeout(timeout);
      if (!resolved) {
        resolved = true;
        reject(err);
      }
    });

    socket.on('close', () => {
      clearTimeout(timeout);
      if (!resolved) {
        resolved = true;
        if (stage === 'QUIT') {
          resolve({ success: true });
        } else {
          reject(new Error(`SMTP connection closed prematurely at stage ${stage}`));
        }
      }
    });
  });
}

