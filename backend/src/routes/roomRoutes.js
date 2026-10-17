const express = require("express");
const { AccessToken } = require("livekit-server-sdk");
const prisma = require("../db");
const cacheService = require("../services/cacheService");

const router = express.Router();

function extractCleanToken(input) {
  if (!input || typeof input !== "string") return "";
  let cleaned = input.trim();
  if (cleaned.startsWith("ridervoice://join/")) {
    cleaned = cleaned.slice("ridervoice://join/".length);
  } else if (cleaned.includes("/join/")) {
    cleaned = cleaned.slice(cleaned.indexOf("/join/") + "/join/".length);
  }
  if (cleaned.includes("token=")) {
    cleaned = cleaned.slice(cleaned.indexOf("token=") + "token=".length);
  }
  cleaned = cleaned.split("?")[0].split("&")[0].split("#")[0].replace(/\/+$/, "").trim();
  return cleaned;
}

router.post("/room/token", async (req, res) => {
  try {
    const { roomName } = req.body;
    const user = req.user;

    if (!roomName) {
      return res
        .status(400)
        .json({ error: "Missing roomName in request body" });
    }

    const cleanInput = extractCleanToken(roomName);
    if (!cleanInput) {
      return res
        .status(400)
        .json({ error: "Missing roomName in request body" });
    }

    let existingRoom = await prisma.room.findUnique({
      where: { name: cleanInput },
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

    let isTokenJoin = false;
    if (!existingRoom) {
      try {
        let joinTokenRecord = await prisma.roomJoinToken.findUnique({
          where: { token: cleanInput },
          select: {
            expiresAt: true,
            room: {
              select: {
                id: true,
                name: true,
                ownerId: true,
                diagnosticsEnabled: true,
              },
            },
          },
        });

        if (!joinTokenRecord && cleanInput.toLowerCase() !== cleanInput) {
          joinTokenRecord = await prisma.roomJoinToken.findUnique({
            where: { token: cleanInput.toLowerCase() },
            select: {
              expiresAt: true,
              room: {
                select: {
                  id: true,
                  name: true,
                  ownerId: true,
                  diagnosticsEnabled: true,
                },
              },
            },
          });
        }

        if (joinTokenRecord) {
          if (new Date() > joinTokenRecord.expiresAt) {
            return res.status(410).json({ error: "Convoy join link has expired" });
          }
          existingRoom = joinTokenRecord.room;
          existingRoom.invites = [];
          isTokenJoin = true;
        }
      } catch (tokenLookupErr) {
      }
    }

    if (!existingRoom && typeof prisma.room.findFirst === "function") {
      try {
        existingRoom = await prisma.room.findFirst({
          where: { name: { equals: cleanInput, mode: "insensitive" } },
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
      } catch (findErr) {
      }
    }

    if (!existingRoom) {
      return res.status(404).json({ error: "Room not found" });
    }

    const isAuthorized =
      existingRoom.ownerId === user.uid ||
      (existingRoom.invites && existingRoom.invites.length > 0);

    if (!isAuthorized || isTokenJoin) {
      const existingInvite = await prisma.rideInvite.findFirst({
        where: { roomId: existingRoom.id, inviteeId: user.uid },
        select: { id: true },
      });
      if (existingInvite) {
        await prisma.rideInvite.update({
          where: { id: existingInvite.id },
          data: { status: "ACCEPTED" },
          select: { id: true },
        });
      } else {
        await prisma.rideInvite.create({
          data: {
            roomId: existingRoom.id,
            inviterId: existingRoom.ownerId,
            inviteeId: user.uid,
            status: "ACCEPTED",
          },
          select: { id: true },
        });
      }
      cacheService.del(`lobby_status:${existingRoom.name}`);
    }

    const participantName = await cacheService.wrap(`user_name:${user.uid}`, 60000, async () => {
      try {
        const dbUser = await prisma.user.findUnique({
          where: { id: user.uid },
          select: { displayName: true, handle: true },
        });
        return dbUser?.displayName || (dbUser?.handle ? `@${dbUser.handle}` : null) || user.name || "Rider";
      } catch (e) {
        return user.name || "Rider";
      }
    });

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
      room: existingRoom.name,
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
      roomName: existingRoom.name,
      diagnosticsEnabled: existingRoom.diagnosticsEnabled ?? activeRun != null,
      testNumber: activeRun?.testNumber || null,
    });
  } catch (error) {
    console.error("Error generating token:", error);
    res.status(500).json({ error: "Failed to generate token" });
  }
});

module.exports = router;
