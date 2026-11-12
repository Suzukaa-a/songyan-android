// @api 1
// @name 语音听写测试
// @input audio
export default async ({ audio }) => {
  if (!audio || audio.byteLength === 0) throw new Error("no audio");
  const bytes = new Uint8Array(audio);
  const riff = String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]);
  const rate = bytes[24] | (bytes[25] << 8) | (bytes[26] << 16) | (bytes[27] << 24);
  return `录音${bytes.byteLength}字节${rate}赫兹${riff}`;
};
