import { render, screen } from "@testing-library/react";
import { expect, test } from "vitest";
import Home from "./page";

test("renders the platform identity and service foundation", () => {
  render(<Home />);
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("기업의 시스템을");
  expect(screen.getByText("AI Service")).toBeInTheDocument();
});
