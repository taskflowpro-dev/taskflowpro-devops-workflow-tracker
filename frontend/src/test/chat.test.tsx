import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import ChatView from "@/components/intelligence/chat";
import type { Message } from "@/lib/intelligence";
import type { TrackedRepository } from "@/lib/repository";
import type { Request } from "@/components/intelligence/shared";

const repository = { id: 7 } as TrackedRepository;
const reply = {
  id: 1,
  role: "assistant",
  content: "Saved reply",
  source: "evidence-guidance",
} as Message;

it("hides previous messages while switching and ignores a late history response", async () => {
  const user = userEvent.setup();
  const pending = Promise.withResolvers<Message[]>();
  const request = vi.fn(async (path: string) => {
    if (path.endsWith("/integrations")) return { ai: { configured: false } };
    if (path.endsWith("/2/messages")) return pending.promise;
    if (path.endsWith("/1/messages")) return [reply];
    return [
      { id: 1, title: "First" },
      { id: 2, title: "Second" },
    ];
  });
  render(<ChatView repository={repository} request={request as Request} />);
  await screen.findByText("Saved reply");
  await user.selectOptions(screen.getByRole("combobox"), "2");
  expect(screen.queryByText("Saved reply")).not.toBeInTheDocument();
  expect(screen.getByRole("status")).toHaveTextContent("Loading conversation");
  await user.selectOptions(screen.getByRole("combobox"), "");
  await act(async () => pending.resolve([{ ...reply, content: "Late reply" }]));
  expect(screen.queryByText("Late reply")).not.toBeInTheDocument();
  expect(screen.queryByText("Saved reply")).not.toBeInTheDocument();
  expect(screen.getByText(/build with context/)).toBeInTheDocument();
});

it("stops loading and permits a message after history fails", async () => {
  const user = userEvent.setup();
  const request = vi.fn(async (path: string) => {
    if (path.endsWith("/integrations")) return { ai: { configured: false } };
    if (path.endsWith("/messages")) throw new Error("History unavailable");
    return [{ id: 1, title: "First" }];
  });
  render(<ChatView repository={repository} request={request as Request} />);
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "History unavailable",
  );
  expect(screen.queryByText(/Loading conversation/)).not.toBeInTheDocument();
  await user.type(screen.getByRole("textbox"), "Explain the build");
  expect(screen.getByRole("button", { name: "Send message" })).toBeEnabled();
});
