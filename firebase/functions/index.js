const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { initializeApp } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');

initializeApp();
const db = getFirestore();

function requireAdmin(request) {
  if (!request.auth) throw new HttpsError('unauthenticated', 'Please sign in first.');
  if (request.auth.token.admin !== true) {
    throw new HttpsError('permission-denied', 'Only the administrator can perform this action.');
  }
}

exports.reviewOrganiserApplication = onCall(async (request) => {
  requireAdmin(request);
  const applicationId = String(request.data?.applicationId ?? '');
  const approve = request.data?.approve;
  const reviewNote = String(request.data?.reviewNote ?? '').trim().slice(0, 500);
  if (!/^\d{10,20}$/.test(applicationId) || typeof approve !== 'boolean') {
    throw new HttpsError('invalid-argument', 'A valid application ID and decision are required.');
  }

  const appRef = db.collection('organiserApplications').doc(applicationId);
  return db.runTransaction(async (tx) => {
    const appSnap = await tx.get(appRef);
    if (!appSnap.exists) throw new HttpsError('not-found', 'Application not found.');
    const application = appSnap.data();
    if (application.status !== 'PENDING') {
      throw new HttpsError('failed-precondition', 'This application has already been reviewed.');
    }
    const uid = application.applicantUid;
    const userRef = db.collection('users').doc(uid);
    const userSnap = await tx.get(userRef);
    if (!userSnap.exists) throw new HttpsError('not-found', 'Applicant account not found.');

    const status = approve ? 'APPROVED' : 'REJECTED';
    tx.update(appRef, {
      status,
      reviewNote,
      reviewedBy: request.auth.uid,
      reviewedAt: FieldValue.serverTimestamp()
    });
    if (approve) tx.update(userRef, { role: 'ORGANISER' });
    return { status };
  }).then(async (result) => {
    // Custom claims are trusted authorization state; only Admin SDK code can set them.
    const appSnap = await appRef.get();
    const uid = appSnap.data().applicantUid;
    const account = await getAuth().getUser(uid);
    const claims = { ...(account.customClaims || {}) };
    if (approve) claims.organiser = true;
    else delete claims.organiser;
    await getAuth().setCustomUserClaims(uid, claims);
    return result;
  });
});

exports.setAccountActive = onCall(async (request) => {
  requireAdmin(request);
  const targetLocalId = Number(request.data?.targetLocalId);
  const active = request.data?.active;
  if (!Number.isSafeInteger(targetLocalId) || typeof active !== 'boolean') {
    throw new HttpsError('invalid-argument', 'A valid account and active status are required.');
  }
  const query = await db.collection('users').where('localId', '==', targetLocalId).limit(1).get();
  if (query.empty) throw new HttpsError('not-found', 'User account not found.');
  const userDoc = query.docs[0];
  if (userDoc.data().role === 'ADMIN' && !active) {
    throw new HttpsError('failed-precondition', 'The administrator account cannot be disabled.');
  }
  await userDoc.ref.update({ active });
  return { active };
});
