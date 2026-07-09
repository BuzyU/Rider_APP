@echo off
echo Fixing Git email privacy issue...

:: Set the email to the public GitHub noreply address for your username (BuzyU)
git config user.email "BuzyU@users.noreply.github.com"

:: Amend the last commit to use the new email
git commit --amend --reset-author --no-edit

:: Push to GitHub
git push

echo.
echo Success! The privacy block has been bypassed.
pause
