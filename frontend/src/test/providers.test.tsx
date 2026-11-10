import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "@/app/providers";
import { api, getToken } from "@/lib/auth";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/dashboard",
}));
vi.mock("@/lib/auth", () => ({
  api: vi.fn(),
  getToken: vi.fn(),
  clearToken: vi.fn(),
}));

function Session() {
  const { user, loading, refresh } = useAuth();
  return (
    <>
      <p>{loading ? "Loading" : (user?.name ?? "Signed out")}</p>
      <button onClick={refresh}>Refresh</button>
    </>
  );
}

it("finishes initialization without a token and can refresh the signed-in user", async () => {
  const user = userEvent.setup();
  vi.mocked(getToken).mockReturnValue(null);
  vi.mocked(api).mockResolvedValue({
    id: 1,
    name: "Alice",
    email: "alice@example.test",
  });
  render(
    <AuthProvider>
      <Session />
    </AuthProvider>,
  );
  await screen.findByText("Signed out");
  expect(replace).toHaveBeenCalledWith("/login");
  expect(api).not.toHaveBeenCalled();
  vi.mocked(getToken).mockReturnValue("synthetic-session");
  await user.click(screen.getByRole("button", { name: "Refresh" }));
  await screen.findByText("Alice");
  expect(api).toHaveBeenCalledWith("/api/users/me", {}, true);
});
