const express = require("express");
const { AccessToken } = require("livekit-server-sdk");
const prisma = require("../db");

const router = express.Router();

router.post("/room/token", async (req, res) => {
  try {
    const { roomName } = req.body;
    const user = req.user;

    if (!roomName) {
      return res
        .status(400)
        .json({ error: "Missing roomName in request body" });
    }

    const existingRoom = await prisma.room.findUnique({
      where: { name: roomName },
      select: {
        id: true,
        name: true,
        ownerId: true,
        diagnosticsEnabled: true,
        invites: {
          where: { inviteeId: user.uid, status: "ACCEPTED" },
          select: { id: true },
          take: 1,
        },
      },
    });

    if (!existingRoom) {
      return res.status(404).json({ error: "Room not found" });
    }

    const isAuthorized =
      existingRoom.ownerId === user.uid || existingRoom.invites.length > 0;
    if (!isAuthorized) {
      return res
        .status(403)
        .json({ error: "No accepted invite found for this room" });
    }

    let participantName = user.name || "Rider";
    try {
      const dbUser = await prisma.user.findUnique({
        where: { id: user.uid },
        select: { displayName: true, handle: true },
      });
      if (dbUser?.displayName) {
        participantName = dbUser.displayName;
      } else if (dbUser?.handle) {
        participantName = `@${dbUser.handle}`;
      }
    } catch (e) {
    }

    const at = new AccessToken(
      process.env.LIVEKIT_API_KEY,
      process.env.LIVEKIT_API_SECRET,
      {
        identity: user.uid,
        name: participantName,
        ttl: "6h",
      },
    );

    at.addGrant({
      roomJoin: true,
      room: roomName,
      canPublish: true,
      canSubscribe: true,
      canPublishData: true,
    });
    const token = await at.toJwt();

    const activeRun = await prisma.diagnosticRun.findFirst({
      where: { convoyId: existingRoom.id, status: "ACTIVE" },
      select: { id: true, testNumber: true },
    });

    res.json({
      token,
      roomName,
      diagnosticsEnabled: existingRoom.diagnosticsEnabled ?? activeRun != null,
      testNumber: activeRun?.testNumber || null,
    });
  } catch (error) {
    console.error("Error generating token:", error);
    res.status(500).json({ error: "Failed to generate token" });
  }
});

module.exports = router;
