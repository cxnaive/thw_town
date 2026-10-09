export const version = "1.21.11";

// minecraft-data 3.117.0 includes the per-attribute display schema. The pinned
// 1.21.11 ServerboundCustomClickActionPacket uses lengthPrefixed(optionalTagCodec).
export const customPackets = {
  1.21: {
    types: {
      packet_common_custom_click_action: [
        "container",
        [
          { name: "id", type: "string" },
          { name: "nbt", type: "ByteArray" },
        ],
      ],
    },
  },
};
