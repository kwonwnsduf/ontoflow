import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, test, vi } from "vitest";
import AuthForm from "./auth-form";
const { replace, api, setSession } = vi.hoisted(() => ({ replace: vi.fn(), api: vi.fn(), setSession: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("./lib/api", () => ({ api, setSession }));
afterEach(() => { cleanup(); vi.clearAllMocks(); });
test("login submits credentials and opens workspace selection", async () => {
  const session = { accessToken: "access", refreshToken: "refresh" }; api.mockResolvedValue(session);
  render(<AuthForm />);
  fireEvent.change(screen.getByLabelText("이메일"), { target: { value: "user@example.com" } });
  fireEvent.change(screen.getByLabelText("비밀번호"), { target: { value: "Strong-password-123" } });
  fireEvent.submit(screen.getByRole("button", { name: "로그인" }).closest("form")!);
  await waitFor(() => expect(replace).toHaveBeenCalledWith("/workspaces"));
  expect(setSession).toHaveBeenCalledWith(session);
  expect(api).toHaveBeenCalledWith("/auth/login", expect.objectContaining({ method: "POST", body: JSON.stringify({ email: "user@example.com", password: "Strong-password-123" }) }));
});
test("registration shows API errors without granting a session", async () => {
  api.mockRejectedValue(new Error("Email already registered")); render(<AuthForm register />);
  fireEvent.submit(screen.getByRole("button", { name: "회원가입" }).closest("form")!);
  expect(await screen.findByRole("alert")).toHaveTextContent("Email already registered");
  expect(setSession).not.toHaveBeenCalled(); expect(replace).not.toHaveBeenCalled();
});
