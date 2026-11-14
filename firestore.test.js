const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");
const nowTs = () => new Date(Date.now() - 1000);

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";
const WS_ID = "ws_gudang_01";
const INVITE_CODE = "GS-8821";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedWorkspaceWithAlice() {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("workspaces").doc(WS_ID).set({
      name: "Gudang Utama Jakarta",
      inviteCode: INVITE_CODE,
      ownerId: ALICE_UID,
      memberUids: [ALICE_UID],
      adminUids: [ALICE_UID],
      jubelioConnected: false,
      createdAt: new Date(),
      updatedAt: new Date(),
    });
    await db.collection("workspace_invites").doc(INVITE_CODE).set({
      inviteCode: INVITE_CODE,
      workspaceId: WS_ID,
      workspaceName: "Gudang Utama Jakarta",
      ownerId: ALICE_UID,
      createdAt: new Date(),
      updatedAt: new Date(),
    });
  });
}

test("Unauthenticated user: denied read/write on all collections", async () => {
  const unauth = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauth.collection("users").doc(ALICE_UID).get());
  await assertFails(unauth.collection("workspaces").get());
  await assertFails(unauth.collection("workspaces").doc(WS_ID).collection("products").get());
  await assertFails(unauth.collection("workspaces").doc(WS_ID).collection("orders").get());
});

test("User profile: owner can create/read/update, non-owner denied, ghost field denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, { email: "alice@example.com", email_verified: true }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Admin",
      email: "alice@example.com",
      activeRole: "ADMIN",
      activeWorkspaceId: WS_ID,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Shadow/ghost field blocked
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isSuperAdmin: true,
      updatedAt: nowTs(),
    })
  );

  // Cross-user read blocked
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
});

test("Products: member can create & query with memberUids filter; unfiltered or non-member denied", async () => {
  await seedWorkspaceWithAlice();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("workspaces").doc(WS_ID).collection("products").doc("prod_1").set({
      workspaceId: WS_ID,
      sku: "SKU-001",
      name: "Kopi Arabika Gayo 250g",
      category: "Beverage",
      rackLocation: "RAK-A1",
      systemStock: 40,
      reservedStock: 2,
      minThreshold: 10,
      unitPrice: 75000,
      syncStatus: "LOCAL_ONLY",
      memberUids: [ALICE_UID],
      createdByUid: ALICE_UID,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Matching filter succeeds
  await assertSucceeds(
    aliceDb.collection("workspaces").doc(WS_ID).collection("products")
      .where("memberUids", "array-contains", ALICE_UID)
      .get()
  );

  // Unfiltered query fails (rules are not filters)
  await assertFails(
    aliceDb.collection("workspaces").doc(WS_ID).collection("products").get()
  );

  // Non-member Bob denied reading Alice's product doc or querying Alice's UID
  await assertFails(
    bobDb.collection("workspaces").doc(WS_ID).collection("products").doc("prod_1").get()
  );
  await assertFails(
    bobDb.collection("workspaces").doc(WS_ID).collection("products")
      .where("memberUids", "array-contains", ALICE_UID)
      .get()
  );
});

test("Workspace join: Bob can join with valid inviteCode, cannot escalate adminUids or join with wrong code", async () => {
  await seedWorkspaceWithAlice();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Wrong code rejected
  await assertFails(
    bobDb.collection("workspaces").doc(WS_ID).update({
      memberUids: [ALICE_UID, BOB_UID],
      lastJoinCode: "WRONG-00",
      updatedAt: nowTs(),
    })
  );

  // Escalation to adminUids rejected
  await assertFails(
    bobDb.collection("workspaces").doc(WS_ID).update({
      memberUids: [ALICE_UID, BOB_UID],
      adminUids: [ALICE_UID, BOB_UID],
      lastJoinCode: INVITE_CODE,
      updatedAt: nowTs(),
    })
  );

  // Valid join succeeds
  await assertSucceeds(
    bobDb.collection("workspaces").doc(WS_ID).update({
      memberUids: [ALICE_UID, BOB_UID],
      lastJoinCode: INVITE_CODE,
      updatedAt: nowTs(),
    })
  );
});

test("Orders: terminal state SELESAI blocks non-admin updates", async () => {
  await seedWorkspaceWithAlice();
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Bob joins workspace
  await assertSucceeds(
    bobDb.collection("workspaces").doc(WS_ID).update({
      memberUids: [ALICE_UID, BOB_UID],
      lastJoinCode: INVITE_CODE,
      updatedAt: nowTs(),
    })
  );

  await assertSucceeds(
    aliceDb.collection("workspaces").doc(WS_ID).collection("orders").doc("ord_1").set({
      workspaceId: WS_ID,
      orderNumber: "ORD-2026-001",
      customerName: "Budi Santoso",
      channel: "Shopee",
      items: [{ sku: "SKU-001", name: "Kopi", rackLocation: "RAK-A1", qty: 2, picked: false }],
      adminInstruction: "Prioritas kirim hari ini",
      status: "SIAP_DIKEMAS",
      totalAmount: 150000,
      timelineEvents: ["Arahan dikirim Admin"],
      assignedByUid: ALICE_UID,
      memberUids: [ALICE_UID, BOB_UID],
      createdAt: nowTs(),
      updatedAt: nowTs(),
    })
  );

  // Bob (warehouse staff) marks order SELESAI
  await assertSucceeds(
    bobDb.collection("workspaces").doc(WS_ID).collection("orders").doc("ord_1").update({
      status: "SELESAI",
      trackingNumber: "JNE123456",
      courier: "JNE",
      updatedAt: nowTs(),
    })
  );

  // Non-admin Bob cannot mutate SELESAI order anymore
  await assertFails(
    bobDb.collection("workspaces").doc(WS_ID).collection("orders").doc("ord_1").update({
      status: "DIKEMAS",
      updatedAt: nowTs(),
    })
  );
});
