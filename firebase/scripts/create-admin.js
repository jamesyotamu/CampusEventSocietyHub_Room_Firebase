// Run once from the project root after downloading a Firebase service-account key.
// Keep serviceAccountKey.json out of source control and never place it in the Android app.
const admin = require('firebase-admin');
const serviceAccount = require('./serviceAccountKey.json');
admin.initializeApp({ credential: admin.credential.cert(serviceAccount) });

(async () => {
  const email = process.env.ADMIN_EMAIL || 'admin@campus.local';
  const password = process.env.ADMIN_PASSWORD;
  if (!password || password.length < 12) {
    throw new Error('Set ADMIN_PASSWORD to a strong password of at least 12 characters.');
  }
  const auth = admin.auth();
  let user;
  try { user = await auth.getUserByEmail(email); }
  catch (e) {
    if (e.code !== 'auth/user-not-found') throw e;
    user = await auth.createUser({ email, password, displayName: 'Campus Administrator', emailVerified: true });
  }
  await auth.setCustomUserClaims(user.uid, { ...(user.customClaims || {}), admin: true });
  const localId = (user.uid.split('').reduce((h, c) => ((h * 31) + c.charCodeAt(0)) | 0, 0) & 0x7fffffff) || 1;
  await admin.firestore().collection('users').doc(user.uid).set({
    uid: user.uid, localId, name: 'Campus Administrator', email,
    role: 'ADMIN', active: true, createdAt: Date.now()
  }, { merge: true });

  const samples = [
    { id: 900000001, title: 'Technology Innovation Workshop', category: 'Academic', date: '2026-10-28', time: '10:00', location: 'Computer Laboratory', organiserName: 'Computer Science Department', description: 'Explore practical technology ideas, innovation and problem-solving.' },
    { id: 900000002, title: 'Business and Entrepreneurship Talk', category: 'Workshop', date: '2026-11-02', time: '14:00', location: 'Main Hall', organiserName: 'Business Students Society', description: 'Learn about starting and managing a small business.' },
    { id: 900000003, title: 'Campus Music Night', category: 'Social', date: '2026-11-06', time: '18:00', location: 'Open Ground', organiserName: 'University Music Society', description: 'An evening of campus performances and music.' },
    { id: 900000004, title: 'Computer Science Club Meeting', category: 'Club', date: '2026-11-10', time: '12:30', location: 'ICT Block', organiserName: 'Computer Science Club', description: 'Meet fellow students and discuss upcoming club activities.' }
  ];
  for (const event of samples) {
    const ref = admin.firestore().collection('events').doc(String(event.id));
    const existing = await ref.get();
    if (!existing.exists) await ref.set({ ...event, organiserUid: user.uid, organiserLocalId: localId, status: 'PUBLISHED', createdAt: Date.now() });
  }
  console.log(`Administrator configured: ${email}`);
  console.log('Sign in with the password supplied in ADMIN_PASSWORD.');
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
