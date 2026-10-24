export type ChatMessage = { role: "user" | "assistant"; content: string };

export interface Providers {
  chat(messages: ChatMessage[]): Promise<string>;
  transcribe(audio: Buffer, mimeType: string): Promise<string>;
}

const mockReplies = [
  "Bonjour ! Je suis le compagnon de bureau (réponse mock).",
  "Bien reçu. En mode live, c'est ici que le LLM répondra.",
  "Le backend fonctionne : la tablette me parle bien.",
];

export const mockProviders: Providers = {
  async chat(messages) {
    const last = messages.at(-1)?.content ?? "";
    const turn = messages.filter((m) => m.role === "user").length;
    return `${mockReplies[(turn - 1) % mockReplies.length]}\n\nTu as dit : « ${last} »`;
  },
  async transcribe(audio) {
    return `(transcription mock, ${Math.round(audio.length / 1024)} Ko d'audio reçus)`;
  },
};

// Mode live : à brancher (Claude pour /chat, fournisseur STT à choisir pour /transcribe).
export const liveProviders: Providers = {
  async chat() {
    throw new Error("Mode live pas encore implémenté pour /chat");
  },
  async transcribe() {
    throw new Error("Mode live pas encore implémenté pour /transcribe");
  },
};
