# Add a teammate during onboarding

System administrators can create teammate access in the first onboarding step without leaving the wizard. Select a role template, enter name/email and a temporary password, then create the account. This reuses the existing user-management endpoint and audit path. It does not send an invitation email; share the temporary password through your organization's approved channel. The user must change it at first login.

The form clears the password after success and updates the team-ready badge. Duplicate email, missing/deleted role and network failures leave a retryable form. Loading failure prevents submission; an empty role catalog directs the administrator to create a template in Settings. Non-administrators do not receive the form, and the existing API rejects their requests.

Browser verification uses actual user creation and duplicate rejection, mandatory password-change state, and axe checks of the resulting success/error screen. Test accounts live only in the isolated H2 UI profile.
